package com.eight87.tonearmboy.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.eight87.tonearmboy.AppGraph
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.playback.notification.PlaybackNotificationProvider
import com.eight87.tonearmboy.ui.settings.CustomNotificationAction
import com.eight87.tonearmboy.ui.settings.SettingsRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground media playback service.
 *
 * Phase E responsibilities (on top of Phase B's scaffolding):
 *
 *  - **E.1 / E.2** install a [PlaybackNotificationProvider]-built
 *    `MediaNotification.Provider` so we own the notification channel
 *    and id; rely on Media3's default `MediaStyle` rendering and on
 *    the `MediaSession`'s metadata + transport state for the actual
 *    notification + lock-screen surface (System UI populates both
 *    from the session on API 33+).
 *  - **E.3** headset / Bluetooth media-button intents flow through
 *    the `MediaSession` automatically; we additionally declare the
 *    `androidx.media3.session.MediaButtonReceiver` in the manifest so
 *    the session survives process death (required by the Media3
 *    `onPlaybackResumption` contract).
 *  - **E.4** `onTaskRemoved` uses Media3's
 *    `pauseAllPlayersAndStopSelf()` so swiping the app from recents
 *    pauses + tears down the foreground service when nothing is
 *    actively playing.
 *  - **E.5** queue + position are persisted via
 *    [QueuePersistence]; on `onCreate` we restore into the player so
 *    the next controller connection sees the previous queue, and we
 *    implement `MediaSession.Callback.onPlaybackResumption` so
 *    Bluetooth / system-UI resume requests can rebuild state without
 *    a controller already being alive.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

  private var mediaSession: MediaSession? = null
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  // R.F.11 — extracted controllers (Playback-F7).
  private lateinit var queuePersistenceController: QueuePersistenceController
  private lateinit var notificationLayoutController: NotificationLayoutController

  override fun onCreate() {
    super.onCreate()
    val player = PlayerHolder.getOrCreate(this)
    setMediaNotificationProvider(PlaybackNotificationProvider.build(this))

    mediaSession = MediaSession.Builder(this, player)
      .setSessionActivity(buildSessionActivityPendingIntent())
      .setCallback(SessionCallback())
      // D.23.2 — custom BitmapLoader. Cascade: user-pinned / auto-fetched
      // cover (resolved from LibraryRepository keyed off EXTRA_TRACK_ID),
      // then the embedded picture frame, then MediaStore legacy album-art
      // keyed off EXTRA_MEDIA_STORE_ALBUM_ID.
      .setBitmapLoader(
        TonearmboyBitmapLoader(
          context = applicationContext,
          pinnedUriResolver = { trackId, albumName, albumArtist ->
            resolvePinnedCoverUri(trackId, albumName, albumArtist)
          },
        ),
      )
      .build()

    queuePersistenceController = QueuePersistenceController(
      storage = QueuePersistence(applicationContext),
      scope = serviceScope,
    )
    // Restore BEFORE attaching the listener so the seed doesn't echo
    // back through onTimelineChanged / onShuffleModeEnabledChanged /
    // onRepeatModeChanged and immediately rewrite the same values.
    queuePersistenceController.restoreInto(player)
    player.addListener(queuePersistenceController.listener { mediaSession?.player })
    queuePersistenceController.startPositionTicker { mediaSession?.player }

    notificationLayoutController = NotificationLayoutController(
      context = applicationContext,
      settings = SettingsRepository(applicationContext),
      scope = serviceScope,
    )
    notificationLayoutController.start(mediaSession!!)
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
    mediaSession

  override fun onTaskRemoved(rootIntent: Intent?) {
    // D.20.3 — flush the latest queue + position synchronously before
    // the service tears down so the 500 ms debounce ticker doesn't lose
    // a pending position write.
    queuePersistenceController.flushSync(mediaSession?.player)
    // Media3-canonical behaviour: if the user swipes the app away while
    // nothing is playing, pause and tear down. While playing, we leave
    // it running so audio + notification stay alive.
    pauseAllPlayersAndStopSelf()
  }

  override fun onDestroy() {
    queuePersistenceController.flushSync(mediaSession?.player)
    queuePersistenceController.cancel()
    notificationLayoutController.stop()
    serviceScope.cancel()
    mediaSession?.run {
      player.release()
      release()
      mediaSession = null
    }
    PlayerHolder.release()
    super.onDestroy()
  }

  /**
   * Resolve the pinned cover URI for the currently-loading track on the
   * BitmapLoader's worker thread. Per-track override wins; album-level
   * pin is the fallback. `runBlocking` is fine here — this is called
   * on `tonearmboy-bitmaploader`, not the main thread.
   */
  private fun resolvePinnedCoverUri(
    trackId: Long,
    albumName: String?,
    albumArtist: String?,
  ): android.net.Uri? = runBlocking {
    val graph = AppGraph.get(applicationContext)
    val trackPinned = (graph.tracks.trackCoverChoice(trackId).first()
      as? com.eight87.tonearmboy.data.AlbumCoverChoice.Pinned)?.uri
    val albumPinned = if (trackPinned == null && !albumName.isNullOrBlank()) {
      val key = com.eight87.tonearmboy.data.albumKey(albumName, albumArtist)
      (graph.albums.albumCoverChoice(key).first()
        as? com.eight87.tonearmboy.data.AlbumCoverChoice.Pinned)?.uri
    } else null
    val pickedUri = trackPinned ?: albumPinned
    pickedUri
      ?.takeIf { com.eight87.tonearmboy.data.albumart.AlbumArtFetcher.pinnedFileStillExists(it) }
      ?.let(android.net.Uri::parse)
  }

  private fun buildSessionActivityPendingIntent(): PendingIntent =
    // R.E.8 — delegated to the factory so this file no longer imports
    // `MainActivity`. See `MainActivitySessionIntentFactory` for the
    // production binding (UI module).
    AppGraph.get(this).sessionActivityIntentFactory.nowPlayingPendingIntent(this)

  // R.F.11 — Persistence + restoration extracted to QueuePersistenceController.
  // Notification CustomLayout wiring extracted to NotificationLayoutController.

  private inner class SessionCallback : MediaSession.Callback {

    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
      // D.23.1 — register the custom session commands so the
      // notification's secondary action button (and any caller
      // MediaController) can dispatch them.
      val available = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
        .add(SessionCommand(COMMAND_REPEAT_TOGGLE, Bundle.EMPTY))
        .add(SessionCommand(COMMAND_SHUFFLE_TOGGLE, Bundle.EMPTY))
        .build()
      // D.23.1 — explicitly advertise the full default player command
      // set. Without this call, the System UI Quick Settings media card
      // sees an empty available-player-command set on connect and
      // silently drops repeat / shuffle / prev / next / play-pause
      // taps. `DEFAULT_PLAYER_COMMANDS` is Media3's canonical
      // "everything the player supports" preset and includes
      // COMMAND_SET_REPEAT_MODE + COMMAND_SET_SHUFFLE_MODE.
      return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        .setAvailableSessionCommands(available)
        .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        .build()
    }

    override fun onCustomCommand(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      customCommand: SessionCommand,
      args: Bundle,
    ): ListenableFuture<SessionResult> {
      val player = session.player
      return when (customCommand.customAction) {
        COMMAND_REPEAT_TOGGLE -> {
          player.repeatMode = nextRepeatMode(player.repeatMode)
          Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        COMMAND_SHUFFLE_TOGGLE -> {
          player.shuffleModeEnabled = !player.shuffleModeEnabled
          Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
      }
    }

    override fun onPlaybackResumption(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      // Run blocking on the main thread is fine here — DataStore reads
      // are tiny and `onPlaybackResumption` is allowed to return a
      // pre-resolved future. Media3 documents a "complete the future
      // as quickly as possible" requirement; the JSON we parse is at
      // most a few KB.
      android.util.Log.i("tonearmboy", "onPlaybackResumption invoked isForPlayback=$isForPlayback")
      return try {
        val resumption = queuePersistenceController.loadResumptionSnapshot()
        if (resumption == null) {
          Futures.immediateFailedFuture(IllegalStateException("no persisted queue"))
        } else {
          Futures.immediateFuture(resumption)
        }
      } catch (t: Throwable) {
        Futures.immediateFailedFuture(t)
      }
    }
  }

  private fun nextRepeatMode(current: Int): Int = when (current) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
  }

  companion object {
    /** D.9a.2 custom session command identifiers. */
    const val COMMAND_REPEAT_TOGGLE = "com.eight87.tonearmboy.action.REPEAT_TOGGLE"
    const val COMMAND_SHUFFLE_TOGGLE = "com.eight87.tonearmboy.action.SHUFFLE_TOGGLE"

    /**
     * D.20.1 — extra carried on the `setSessionActivity` PendingIntent
     * so MainActivity can route the user to Now Playing on notification
     * tap instead of the last-active screen.
     */
    const val EXTRA_DEEPLINK = "tonearmboy.deeplink"
    const val DEEPLINK_NOW_PLAYING = "now_playing"
  }
}
