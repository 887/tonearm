package com.eight87.tonearmboy.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.eight87.tonearmboy.AppGraph
import com.eight87.tonearmboy.playback.PlaybackService
import com.eight87.tonearmboy.ui.nav.routes.Register
import com.eight87.tonearmboy.ui.playing.MiniPlayer
import com.eight87.tonearmboy.ui.playing.NowPlayingScreen
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode
import com.eight87.tonearmboy.ui.settings.CustomBarAction
import com.eight87.tonearmboy.ui.settings.MusicSourcesDialog
import com.eight87.tonearmboy.ui.settings.catalog.LocalHighlightedSettingId
import kotlinx.coroutines.launch

/**
 * R.E.3 — root composable. Builds [RouteScope], dispatches each
 * destination through its `Register(scope)` extension. Per-destination
 * data plumbing lives in `routes/{Library,Playing,Settings}Routes.kt`.
 *
 * G+ — NowPlaying is no longer a nav route. It's an overlay sheet
 * rendered above the library at the app root, controlled by a shared
 * `Animatable<Float>` (0 = collapsed, mini-player visible; 1 = fully
 * expanded, NowPlaying covers the library). The mini-player forwards
 * vertical drags directly to the sheet progress, and a
 * [NestedScrollConnection] on the open sheet drains overscroll-down
 * back into the sheet progress so the queue's pull-down-from-top
 * keeps the same continuous-drag feel as the open / close gesture.
 */
@OptIn(UnstableApi::class)
@Composable
fun TonearmboyApp(
  graph: AppGraph,
  /** D.20.1 — bumped each time `MainActivity.handleIntent` receives a notification deeplink. */
  deeplinkNonce: Int = 0,
  pendingDeeplink: String? = null,
  onDeeplinkConsumed: () -> Unit = {},
) {
  val backStack = remember { TonearmboyBackStack(LibraryRoot) }
  val playback = graph.playbackUiController
  val playbackState by playback.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val sectionTitle = remember { mutableStateOf("tonearmboy") }
  val highlightedSettingId = remember { mutableStateOf<String?>(null) }
  var showMusicSourcesDialog by remember { mutableStateOf(false) }

  // G+ — sheet progress (0..1). Owned at the app root so the mini-player,
  // the sheet overlay, the back-handler, and the deeplink reactor all
  // drive the same value.
  val sheetProgress = remember { Animatable(0f) }
  val coroutineScope = rememberCoroutineScope()

  // Hoisted so the sheet's NestedScrollConnection can pre-empt drag-
  // down (close-sheet) only when the queue is already at its top.
  // Otherwise the queue scrolls normally without the sheet stealing
  // events.
  val nowPlayingListState = rememberLazyListState()

  // R.E.6 — settings → playback mirrors.
  PlaybackSettingsBridge(playback, playback, graph.settingsRepository)

  // D.20.1 — react to a notification deeplink: animate the sheet open.
  LaunchedEffect(deeplinkNonce, pendingDeeplink) {
    if (pendingDeeplink == PlaybackService.DEEPLINK_NOW_PLAYING) {
      sheetProgress.animateTo(1f)
      onDeeplinkConsumed()
    }
  }

  // Keep the MediaController bound for the lifetime of the activity.
  LaunchedEffect(Unit) {
    // D.9b.1 — give the controller a library handle for ReplayGain
    // album lookups before it sees the first track transition.
    playback.setLibrary(graph.tracks)
    playback.connect()
  }

  // MiniPlayer-only settings reads kept here; everything else lives on RouteScope.
  val customBarAction by graph.settingsRepository.customBarAction.flow
    .collectAsStateWithLifecycle(initialValue = CustomBarAction.Default)
  val albumCoversMode by graph.settingsRepository.albumCoversMode.flow
    .collectAsStateWithLifecycle(initialValue = AlbumCoversMode.Default)

  val openNowPlayingSheet: () -> Unit = remember {
    { coroutineScope.launch { sheetProgress.animateTo(1f) }; Unit }
  }
  val closeSheet: () -> Unit = remember {
    { coroutineScope.launch { sheetProgress.animateTo(0f) }; Unit }
  }

  val scope = rememberRouteScope(
    graph = graph,
    backStack = backStack,
    snackbar = snackbar,
    showMusicSourcesDialog = { showMusicSourcesDialog = true },
    openNowPlayingSheet = openNowPlayingSheet,
  )

  val showMiniPlayer = playbackState.hasMedia

  // BackHandler: when the sheet is open, back collapses it instead
  // of popping the underlying library back-stack.
  BackHandler(enabled = sheetProgress.value > 0f) {
    closeSheet()
  }

  CompositionLocalProvider(
    LocalSectionTitle provides sectionTitle,
    LocalHighlightedSettingId provides highlightedSettingId,
  ) {
    // Avoid `BoxWithConstraints` here — it's a SubcomposeLayout, which
    // forces a full extra composition pass for the children after
    // the parent's measure resolves. On cold start that's an expensive
    // round-trip we don't need: we only consume `maxHeight` to compute
    // the sheet's drag math + the inner-stack height. `LocalConfiguration`
    // gives us the same screen height ahead of measurement, no
    // subcompose required.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val density = LocalDensity.current
    val screenHeightPx = with(density) { screenHeightDp.toPx() }.coerceAtLeast(1f)
    Box(modifier = Modifier.fillMaxSize()) {

      // Mini-player peek height. Two-row layout — info row (48-dp art
      // + title + close) plus the transport row, with a 2-dp progress
      // line pinned to the bottom edge. Auxio-style; the draggable
      // slider + timestamps were removed so the slot dropped from
      // 180 dp to ~118 dp without clipping anything.
      val peekDp = 118.dp
      val peekPx = with(density) { peekDp.toPx() }
      val effectivePeekPx = if (showMiniPlayer) peekPx else 0f

      val progress = sheetProgress.value
      // Auxio-style staggered crossfade. Mini-player visible 0..0.5,
      // gone 0.5..1. NowPlaying gone 0..0.5, fading in 0.5..1.
      val miniAlpha = (1f - min(progress * 2f, 1f)).coerceIn(0f, 1f)
      val nowPlayingAlpha = (max(progress - 0.5f, 0f) * 2f).coerceIn(0f, 1f)

      // Drag-delta forwarder. delta < 0 = user dragged up = sheet rises.
      // The drag-start progress is captured on the first delta of each
      // gesture so [onSheetDragSettle] can snap based on movement
      // *direction* (Auxio-style flick commit) rather than absolute
      // position — a small upward drag from peek (e.g. progress=0.15)
      // is decisively "wants to open" and should commit to 1.0, not
      // snap back to 0.0 just because absolute progress < 0.5.
      val dragStartProgress = remember { mutableStateOf<Float?>(null) }
      val onSheetDragDelta: (Float) -> Unit = { delta ->
        coroutineScope.launch {
          if (dragStartProgress.value == null) {
            dragStartProgress.value = sheetProgress.value
          }
          val travel = (screenHeightPx - effectivePeekPx).coerceAtLeast(1f)
          val next = (sheetProgress.value - delta / travel).coerceIn(0f, 1f)
          sheetProgress.snapTo(next)
        }
      }
      val onSheetDragSettle: () -> Unit = {
        coroutineScope.launch {
          val start = dragStartProgress.value ?: 0f
          val end = sheetProgress.value
          val moved = end - start
          val flickThreshold = 0.05f  // 5% of sheet travel = decisive flick
          val target = when {
            moved > flickThreshold -> 1f       // upward flick → open
            moved < -flickThreshold -> 0f      // downward flick → close
            // No meaningful movement (tap-like) — fall back to position.
            else -> if (end >= 0.5f) 1f else 0f
          }
          sheetProgress.animateTo(target)
          dragStartProgress.value = null
        }
      }

      // ---- Layer 1: library, with bottom inset = peek height ----
      // No bottomBar; the mini-player lives in the sheet (Layer 2).
      // Consume only top + horizontal Scaffold insets — the bottom
      // inset (system nav bar) would stack on top of `libraryBottomPad`
      // and leave a gap between the library and the sheet (the sheet
      // is at the BoxWithConstraints level and reaches the screen's
      // physical bottom, ignoring the nav-bar inset).
      val libraryBottomPad = if (showMiniPlayer) peekDp else 0.dp
      val layoutDir = androidx.compose.ui.platform.LocalLayoutDirection.current
      Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
      ) { innerPadding ->
        NavDisplay(
          backStack = backStack.backStack,
          onBack = { backStack.pop() },
          modifier = Modifier
            .fillMaxSize()
            .padding(
              start = innerPadding.calculateStartPadding(layoutDir),
              end = innerPadding.calculateEndPadding(layoutDir),
              bottom = libraryBottomPad,
            ),
          entryProvider = entryProvider {
            entry<LibraryRoot> { it.Register(scope) }
            entry<AlbumDetail> { it.Register(scope) }
            entry<ArtistDetail> { it.Register(scope) }
            entry<GenreDetail> { it.Register(scope) }
            entry<Search> { it.Register(scope) }
            entry<PlaylistDetail> { it.Register(scope) }
            entry<PlaylistTrackPicker> { it.Register(scope) }
            entry<CustomTabEditor> { it.Register(scope) }
            entry<SettingsRootDest> { it.Register(scope) }
            entry<SettingsAbout> { it.Register(scope) }
            entry<SettingsLicenses> { it.Register(scope) }
            entry<SettingsSearch> { it.Register(scope) }
            entry<SettingsLookAndFeel> { it.Register(scope) }
            entry<SettingsPersonalize> { it.Register(scope) }
            entry<SettingsContent> { it.Register(scope) }
            entry<SettingsMusicSources> { it.Register(scope) }
            entry<SettingsAudio> { it.Register(scope) }
            entry<SettingsCoverArtProviders> { it.Register(scope) }
            entry<SettingsPipedInstances> { it.Register(scope) }
            entry<SettingsMusicBrainzProvider> { it.Register(scope) }
          },
        )

        // R.E.4 / R.E.5 — global overlays rendered once at the app root.
        scope.addToPlaylist.Overlay()
        scope.playlistBackup.Overlay()

        // D.17.3 — Music sources dialog.
        if (showMusicSourcesDialog) {
          MusicSourcesDialog(
            settings = graph.settingsRepository,
            scanner = graph.scanner,
            onDismiss = { showMusicSourcesDialog = false },
          )
        }
      }

      // ---- Layer 2: bottom-anchored sheet (Auxio-style) ----
      // Outer Box anchored at bottom, height grows from peek to full
      // screen as progress goes 0 → 1. Solid surface background covers
      // the library above as the sheet expands. Inside: mini-player
      // and full NowPlaying are layered z-stack; both anchored at the
      // top of the sheet's inner area, mini-player sized to peekDp and
      // NowPlaying sized to fillMaxSize. They cross-fade in place via
      // the staggered alpha ratios — at progress=0 only mini-player is
      // visible (the bottom strip); at progress=1 only NowPlaying is
      // visible (full screen).
      if (showMiniPlayer) {
        val sheetHeightPx = effectivePeekPx + progress * (screenHeightPx - effectivePeekPx)
        val sheetHeightDp = with(density) { sheetHeightPx.toDp() }

        // NestedScrollConnection: queue overscroll drains / refills
        // sheet progress (Auxio "pull-down to collapse" / "pull-up to
        // re-expand" when the sheet is partially closed). At fling,
        // commit based on the *direction* of the last drag delta —
        // any meaningful drag toward open commits to 1, any toward
        // close commits to 0. Position-based fallback only when no
        // drag happened during the gesture.
        val nestedDragDirection = remember { mutableStateOf(0) }
        val sheetNestedScroll = remember(screenHeightPx, effectivePeekPx) {
          object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
              // Only react to direct user drag, NOT to fling /
              // overscroll-bounce side-effects. Fling-source events
              // arriving after release would otherwise call snapTo
              // and cancel the settle's in-flight animateTo, leaving
              // the sheet stuck mid-progress.
              if (source != NestedScrollSource.UserInput) return Offset.Zero
              // Drag-up: if sheet partially open, finish opening first.
              if (available.y < 0f && sheetProgress.value < 1f) {
                val travel = (screenHeightPx - effectivePeekPx).coerceAtLeast(1f)
                val delta = -available.y / travel
                nestedDragDirection.value = -1
                coroutineScope.launch {
                  sheetProgress.snapTo((sheetProgress.value + delta).coerceAtMost(1f))
                }
                return Offset(0f, available.y)
              }
              // Drag-down at top of queue: pre-empt before LazyColumn
              // overscroll eats the leftover.
              if (available.y > 0f &&
                nowPlayingListState.firstVisibleItemIndex == 0 &&
                nowPlayingListState.firstVisibleItemScrollOffset == 0
              ) {
                val travel = (screenHeightPx - effectivePeekPx).coerceAtLeast(1f)
                val delta = available.y / travel
                nestedDragDirection.value = 1
                coroutineScope.launch {
                  sheetProgress.snapTo((sheetProgress.value - delta).coerceAtLeast(0f))
                }
                return Offset(0f, available.y)
              }
              return Offset.Zero
            }
            override fun onPostScroll(
              consumed: Offset,
              available: Offset,
              source: NestedScrollSource,
            ): Offset {
              if (source != NestedScrollSource.UserInput) return Offset.Zero
              if (available.y > 0f) {
                val travel = (screenHeightPx - effectivePeekPx).coerceAtLeast(1f)
                val delta = available.y / travel
                nestedDragDirection.value = 1
                coroutineScope.launch {
                  sheetProgress.snapTo((sheetProgress.value - delta).coerceAtLeast(0f))
                }
                return Offset(0f, available.y)
              }
              return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
              val dir = nestedDragDirection.value
              val target = when {
                dir > 0 -> 0f                                          // was closing
                dir < 0 -> 1f                                          // was opening
                else -> if (sheetProgress.value >= 0.5f) 1f else 0f    // no drag this gesture
              }
              sheetProgress.animateTo(target)
              nestedDragDirection.value = 0
              return Velocity.Zero
            }
          }
        }

        // Parent-level drag handler. Catches drags on areas NOT
        // claimed by descendants (TopAppBar, cover/title gaps,
        // transport-row spacing). Children with their own drag/scroll
        // (LazyColumn, slider) consume their drags first; this fires
        // only on the leftover. Combined with the NestedScroll on the
        // same box, drags everywhere on the sheet end up driving
        // sheetProgress one way or another.
        val sheetDraggable = rememberDraggableState { delta ->
          onSheetDragDelta(delta)
        }
        Box(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(sheetHeightDp)
            .background(MaterialTheme.colorScheme.surface)
            .clipToBounds()
            .nestedScroll(sheetNestedScroll)
            .draggable(
              state = sheetDraggable,
              orientation = Orientation.Vertical,
              onDragStopped = { onSheetDragSettle() },
            ),
        ) {
          // Inner stack: anchored to the TOP of the sheet, fixed at full
          // screen height so the layout doesn't reflow as the sheet grows.
          // The MiniPlayer is ALWAYS composed when [showMiniPlayer] —
          // gating it with `if (alpha > 0f)` dropped the MiniPlayer
          // composable mid-drag (its pointerInput owns the drag), which
          // canceled the drag without firing onDragEnd → sheet stuck
          // mid-progress. The full NowPlayingScreen behind it is NOT
          // the gesture owner, so it's safe to gate on visibility —
          // and that's a real cold-start win since NowPlayingScreen is
          // a heavy composable (queue list, transport, cover load).
          Box(
            modifier = Modifier
              .align(Alignment.TopCenter)
              .fillMaxWidth()
              .height(screenHeightDp),
          ) {
            // NowPlaying full surface (back of stack). Visible at expanded.
            // Lazy-mount: only compose when actually about to be visible
            // (`progress > 0.45` ≈ where alpha leaves zero per the
            // staggered-crossfade math). Defer the heavy composition
            // off the first frame — sheet collapsed at progress=0 means
            // first frame just paints MiniPlayer.
            if (progress > 0.45f) Box(modifier = Modifier.fillMaxSize().alpha(nowPlayingAlpha)) {
              NowPlayingScreen(
                nowPlayingState = playback,
                transport = playback,
                queueCommands = playback,
                onBack = closeSheet,
                albumCoversMode = albumCoversMode,
                onSaveQueueAsPlaylist = { mediaIds ->
                  val trackIds = mediaIds.mapNotNull { it.toLongOrNull() }
                  scope.addToPlaylist.requestBulk(trackIds)
                },
                onDeleteCurrentTrack = {
                  // G+ — pocket-tap-proof typed-confirm delete. The
                  // dialog gates the actual filesystem delete; we only
                  // hit this lambda after the user has typed "y/yes"
                  // and tapped the confirm button. Resolve the active
                  // queue mediaId to a Track and reuse the existing
                  // library delete flow (SAF consent + cache invalidation).
                  val q = playback.queue.value
                  val activeMediaId = q.items
                    .getOrNull(q.currentIndex)
                    ?.mediaId
                    ?.toLongOrNull()
                    ?: return@NowPlayingScreen
                  scope.applicationScope.launch {
                    val track = graph.tracks.trackById(activeMediaId) ?: return@launch
                    scope.onDeleteTracks(listOf(track))
                  }
                },
                nowPlayingListState = nowPlayingListState,
              )
            }

            // Mini-player (front of stack). Sized to peek, anchored at
            // top of inner area — visible at peek state, fades out by
            // progress=0.5. ALWAYS composed — see comment above.
            Box(
              modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(peekDp)
                .alpha(miniAlpha),
            ) {
              MiniPlayer(
                state = playbackState,
                onTogglePlayPause = playback::togglePlayPause,
                onClose = playback::stop,
                onExpand = openNowPlayingSheet,
                onSkipNext = playback::seekToNext,
                onSkipPrevious = playback::seekToPrevious,
                onPlayButtonLongPress = { playback.performCustomBarAction(customBarAction) },
                onToggleShuffle = playback::toggleShuffle,
                onCycleRepeat = playback::cycleRepeatMode,
                onSeekTo = playback::seekTo,
                albumCoversMode = albumCoversMode,
                onSheetDragDelta = onSheetDragDelta,
                onSheetDragSettle = onSheetDragSettle,
              )
            }
          }
        }
      }
    }
  }
}
