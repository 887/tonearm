package com.eight87.tonearmboy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.eight87.tonearmboy.playback.PlaybackService
import com.eight87.tonearmboy.theme.LocalAlbumPalette
import com.eight87.tonearmboy.theme.TonearmboyTheme
import com.eight87.tonearmboy.ui.nav.TonearmboyApp
import com.eight87.tonearmboy.ui.permission.RequireAudioPermission
import com.eight87.tonearmboy.ui.permission.RequirePostNotifications
import com.eight87.tonearmboy.data.watcher.LibraryWatcherService
import com.eight87.tonearmboy.ui.settings.BaseTheme
import com.eight87.tonearmboy.ui.settings.ThemePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@UnstableApi
class MainActivity : ComponentActivity() {

  /**
   * D.20.1 — back-channel from `onNewIntent` (and the initial
   * `onCreate` intent) into the Compose tree. The TonearmboyApp
   * composable observes [deeplinkNonce] and pushes
   * `Destinations.NowPlaying` onto its back stack whenever the
   * value changes. We use a monotonically-increasing nonce so
   * re-receiving the same intent (e.g. the user taps the
   * notification twice) always produces a fresh recomposition.
   */
  private val deeplinkNonce = mutableStateOf(0)
  private val pendingDeeplink = mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val graph = AppGraph.get(applicationContext)

    // D.20.1 — read the launch intent for the notification deeplink.
    handleIntent(intent)

    setContent {
      // Cold-start side jobs that don't gate the first frame — fire
      // them post-composition on the main scope so they don't run on
      // the critical path the way the original `applicationScope.launch
      // { ... }` chain in onCreate did. shutterboy ships nothing of
      // the sort in MainActivity and cold-starts ~400 ms faster, so
      // the goal here is "look like shutterboy."
      LaunchedEffect(Unit) {
        // D.9d.2 — re-arm the watcher service if Automatic reloading
        // was on across a process restart. Idempotent against the
        // service's own start logic.
        val on = graph.settingsRepository.automaticReloading.flow.first()
        if (on) LibraryWatcherService.start(applicationContext)
      }
      LaunchedEffect(Unit) {
        // D.17.3.6 — write the default music-source mode on fresh
        // install so the first launch scans MediaStore without
        // opening a settings page. Also idempotent.
        graph.settingsRepository.firstLaunchInitialise()
      }

      // R.B.5 — per-key Flow reads. Each subscription is its own
      // narrow stream so toggling, e.g., albumArtTintEnabled doesn't
      // recompose the theme-mode `when` block.
      val theme by graph.settingsRepository.theme.flow
        .collectAsStateWithLifecycle(initialValue = ThemePreference.Default)
      val baseTheme by graph.settingsRepository.baseTheme.flow
        .collectAsStateWithLifecycle(initialValue = BaseTheme.Default)
      val albumArtTintEnabled by graph.settingsRepository.albumArtTintEnabled.flow
        .collectAsStateWithLifecycle(initialValue = true)
      val albumArtBackgroundEnabled by graph.settingsRepository.albumArtBackgroundEnabled.flow
        .collectAsStateWithLifecycle(initialValue = true)
      val customChromeTintRaw by graph.settingsRepository.customChromeTint.flow
        .collectAsStateWithLifecycle(initialValue = 0L)
      val customChromeTint: androidx.compose.ui.graphics.Color? =
        if (customChromeTintRaw == 0L) null
        else androidx.compose.ui.graphics.Color(0xFF000000.toInt() or customChromeTintRaw.toInt())
      val systemDark = isSystemInDarkTheme()
      val resolvedDark = when (theme) {
        ThemePreference.System -> systemDark
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
      }

      // D.20.4 — observe the playback state so we can refresh the
      // album palette whenever the playing track changes.
      val playbackState by graph.playbackUiController.state.collectAsStateWithLifecycle()
      LaunchedEffect(playbackState.trackId, playbackState.mediaStoreAlbumId) {
        graph.albumPaletteSource.setNowPlaying(
          trackId = playbackState.trackId,
          albumId = playbackState.mediaStoreAlbumId,
        )
      }
      val albumPalette by graph.albumPaletteSource.palette.collectAsStateWithLifecycle()
      val albumCoverUri by graph.albumPaletteSource.coverUri.collectAsStateWithLifecycle()
      val showAlbumArtBackground = albumArtTintEnabled && albumArtBackgroundEnabled &&
        albumCoverUri != null

      CompositionLocalProvider(LocalAlbumPalette provides albumPalette) {
        TonearmboyTheme(
          darkTheme = resolvedDark,
          baseTheme = baseTheme,
          albumArtTintEnabled = albumArtTintEnabled,
          customChromeTint = customChromeTint,
          albumArtBackgroundActive = showAlbumArtBackground,
        ) {
          androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            if (showAlbumArtBackground) {
              com.eight87.tonearmboy.theme.AlbumArtBackground(
                coverUri = albumCoverUri,
                modifier = Modifier.fillMaxSize(),
              )
            }
            Surface(
              modifier = Modifier.fillMaxSize(),
              color = if (showAlbumArtBackground) androidx.compose.ui.graphics.Color.Transparent
              else MaterialTheme.colorScheme.background,
            ) {
            // D.19 — wrap the app in the runtime-permission gate so a
            // fresh install on a real phone walks the system grant flow
            // for READ_MEDIA_AUDIO. On grant we trigger a full library
            // rescan immediately so the user doesn't need to find a
            // "Rescan music" button in Settings to populate the library.
            RequireAudioPermission(
              onGranted = {
                graph.applicationScope.launch {
                  graph.scanner.rescanNow()
                }
              },
            ) {
              // D.23.3 — POST_NOTIFICATIONS runtime gate. API 33+ only;
              // pass-through on earlier versions. Denial does not block
              // playback; only the in-tray notification ribbon is
              // affected (Quick Settings + lock screen are
              // MediaSession-driven and don't need POST_NOTIFICATIONS).
              RequirePostNotifications {
                TonearmboyApp(
                  graph = graph,
                  deeplinkNonce = deeplinkNonce.value,
                  pendingDeeplink = pendingDeeplink.value,
                  onDeeplinkConsumed = { pendingDeeplink.value = null },
                )
              }
            }
          }
        }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  /**
   * D.20.1 — extract the `tonearmboy.deeplink` extra and bump the nonce
   * so the Compose layer reacts. The actual back-stack push happens
   * in `TonearmboyApp` because that's where the `TonearmboyBackStack`
   * lives.
   */
  internal fun handleIntent(intent: Intent?) {
    if (intent == null) return
    val deeplink = intent.getStringExtra(PlaybackService.EXTRA_DEEPLINK) ?: return
    pendingDeeplink.value = deeplink
    deeplinkNonce.value = deeplinkNonce.value + 1
    // Clear EXTRA_DEEPLINK from the activity's stored intent immediately
    // so a later recreation (process kill from memory pressure, config
    // change, task-switcher resurrection) doesn't re-read this same
    // notification deeplink and re-open the NowPlaying sheet on top of
    // wherever the user navigated since. The deeplink semantically
    // belongs to the *one* notification tap that set it; stripping it
    // here makes the intent idempotent for re-reads.
    intent.removeExtra(PlaybackService.EXTRA_DEEPLINK)
    setIntent(intent)
  }

  companion object {
    /**
     * Splash safety-net timeout. The original D.22.2 cap was 600 ms,
     * picked from the Android guidance "long enough to hide a fast
     * handshake, short enough to never feel like a hang." User
     * complaint that cold-start splash lingers compared to peer apps
     * (Auxio etc.) — they don't gate on a Media3 controller handshake
     * at all. We drop the cap to 150 ms: long enough for the common
     * case where the service is already warm and `awaitConnected()`
     * resolves on the first frame, short enough that a cold handshake
     * lands the user on `NowPlaying`'s own Connecting state (D.22.3)
     * instead of staring at the launcher icon. The Connecting state
     * has its own progress indicator and dismisses cleanly when the
     * controller binds, so there's no UX regression from the shorter
     * cap — only a perceived-snappiness win.
     */
    internal const val SPLASH_HOLD_TIMEOUT_MS: Long = 150L
  }
}
