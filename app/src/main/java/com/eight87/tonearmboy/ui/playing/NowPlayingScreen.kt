package com.eight87.tonearmboy.ui.playing

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.media3.common.Player
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.playback.ConnectionPhase
import com.eight87.tonearmboy.playback.NowPlayingState
import com.eight87.tonearmboy.playback.PlaybackUiController
import com.eight87.tonearmboy.playback.QueueCommands
import com.eight87.tonearmboy.playback.TransportCommands
import com.eight87.tonearmboy.playback.PlaybackUiState
import com.eight87.tonearmboy.playback.QueueItem
import com.eight87.tonearmboy.playback.QueueSnapshot
import com.eight87.tonearmboy.ui.library.CoverArt
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen Now Playing.
 *
 * D.24.2 — the queue is no longer a separate `ModalBottomSheet`. The
 * whole surface is one scrollable `LazyColumn` with the now-playing
 * card on top (cover + title + scrubber + transport row) and the
 * queue inlined directly below. The queue-shortcut icon in the top
 * app bar is repurposed as `animateScrollToItem` to the "Up next"
 * header so users on a short device can jump to the queue without
 * scrolling.
 *
 * Owns its own connect/release pair: while the host activity also
 * keeps a long-lived [PlaybackUiController] connected, this screen
 * still calls `connect()` (idempotent) on entry to be safe in deep-
 * link scenarios where Now Playing is the first composable rendered.
 */
@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun NowPlayingScreen(
  // R.C.1 — narrow facets: read-only state + write-only commands.
  // The wholesale `PlaybackUiController` is no longer in this
  // signature; AppGraph hands the same instance through three
  // narrow contracts, but a stray ReplayGain or settings-mirror
  // call from this screen now fails to compile.
  nowPlayingState: NowPlayingState,
  transport: TransportCommands,
  queueCommands: QueueCommands,
  onBack: () -> Unit,
  albumCoversMode: AlbumCoversMode = AlbumCoversMode.Balanced,
  onSaveQueueAsPlaylist: ((mediaIds: List<String>) -> Unit)? = null,
  /**
   * G+ — typed-confirm delete. Fires when the user taps the trash
   * icon AND types "y" / "yes" in the confirm dialog. Caller is
   * responsible for translating the active mediaId into the Track
   * row + dispatching through the existing `onDeleteTracks` flow
   * (which handles the SAF consent + cache invalidation). Null
   * disables the button entirely.
   */
  onDeleteCurrentTrack: (() -> Unit)? = null,
  /** G+ — caller-provided listState so the app-root sheet handler can
   *  query whether the queue is at its top before deciding whether to
   *  pre-empt drag-down for sheet collapse. */
  nowPlayingListState: LazyListState? = null,
  /**
   * Round 3 — per-track cover override URI. When non-null this wins
   * over the legacy MediaStore album-art path so the now-playing card
   * shows the song's own cover (track-pinned, or filled in by the
   * bulk-art worker walking each song's filename).
   */
  trackCoverUriOverride: String? = null,
) {
  val state by nowPlayingState.state.collectAsStateWithLifecycle()
  val queueSnapshot by nowPlayingState.queue.collectAsStateWithLifecycle()
  // Always call `rememberLazyListState()` so its slot table position
  // stays stable across recompositions. Use the hoisted state when
  // provided; otherwise fall back to the local one.
  val fallbackListState = rememberLazyListState()
  val listState = nowPlayingListState ?: fallbackListState
  // G+ — typed-confirm delete dialog state.
  var showDeleteConfirm by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.playing_top_bar_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.playing_cd_back),
            )
          }
        },
        actions = {
          // G+ — Delete current track. To the LEFT of the save-queue
          // button. Opens a typed-confirm dialog ("y/yes" or "n/no")
          // so a pocket-tap can't trigger a delete. The actual SAF
          // consent + filesystem delete happens via the same
          // `onDeleteTracks` flow the library uses.
          if (onDeleteCurrentTrack != null && state.hasMedia) {
            IconButton(
              onClick = { showDeleteConfirm = true },
              modifier = Modifier.semantics { testTag = "now_playing_delete" },
            ) {
              Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.playing_cd_delete_current),
              )
            }
          }
          // D.29.1 — replaces D.24.2's scroll-to-queue affordance.
          // The queue lives inline (D.24.2) so a "scroll to queue"
          // button is redundant — a swipe gets the user there. Repurpose
          // the slot for "Save queue as playlist", which dispatches
          // through the existing `PlaylistPickerSheet` (the same one
          // multi-select uses) seeded with the queue's media ids.
          IconButton(
            onClick = {
              onSaveQueueAsPlaylist?.invoke(queueSnapshot.items.map { it.mediaId })
            },
            modifier = Modifier.semantics { testTag = "now_playing_save_queue" },
            enabled = state.hasMedia && queueSnapshot.items.isNotEmpty(),
          ) {
            Icon(
              Icons.AutoMirrored.Filled.PlaylistAdd,
              contentDescription = stringResource(R.string.playing_cd_save_queue_as_playlist),
            )
          }
        },
      )
    },
  ) { innerPadding ->
    when (resolveSubState(state)) {
      NowPlayingSubState.Connecting -> NowPlayingConnecting(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(24.dp),
      )
      NowPlayingSubState.ConnectedEmpty -> NowPlayingEmpty(
        onBack = onBack,
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(24.dp),
      )
      NowPlayingSubState.ConnectedWithMedia -> {
        // G+ — pull-down-from-top is now handled by the app-root sheet
        // wrapper's [NestedScrollConnection] (see TonearmboyApp), which
        // drains queue overscroll into the same shared sheet progress
        // that the mini-player drag drives. NowPlayingScreen no longer
        // owns a parallel pull-down accumulator.
        NowPlayingMergedSurface(
          state = state,
          queueSnapshot = queueSnapshot,
          listState = listState,
          albumCoversMode = albumCoversMode,
          onSeek = transport::seekTo,
          onTogglePlayPause = transport::togglePlayPause,
          onSeekBackward = transport::seekBackward,
          onSeekForward = transport::seekForward,
          onSeekToPrevious = transport::seekToPrevious,
          onSeekToNext = transport::seekToNext,
          onToggleShuffle = transport::toggleShuffle,
          onCycleRepeat = transport::cycleRepeatMode,
          onJumpToQueueIndex = queueCommands::seekToQueueIndex,
          onRemoveQueueItem = queueCommands::removeQueueItem,
          onMoveQueueItem = queueCommands::moveQueueItem,
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .semantics { testTag = "now_playing_screen" },
          trackCoverUriOverride = trackCoverUriOverride,
        )
      }
    }
  }

  // G+ — typed-confirm delete dialog. The user has to type "y" / "yes"
  // (case-insensitive) into the field before the Delete button enables;
  // typing "n" / "no" or anything else keeps it disabled and the user
  // can dismiss with Cancel or back. Pocket-tap-proof: a single tap on
  // the trash icon opens this dialog, but no accidental sequence of
  // taps can complete the delete.
  if (showDeleteConfirm) {
    TypedConfirmDialog(
      title = stringResource(R.string.playing_delete_confirm_title),
      message = stringResource(R.string.playing_delete_confirm_message, state.title.ifEmpty { "" }),
      onConfirm = {
        showDeleteConfirm = false
        onDeleteCurrentTrack?.invoke()
      },
      onDismiss = { showDeleteConfirm = false },
    )
  }
}

/**
 * G+ — pocket-tap-proof confirm. Dialog with a TextField where the
 * user has to type one of `y / yes` (case-insensitive) to enable the
 * Confirm button. `n / no` (or anything else) leaves it disabled.
 */
@Composable
private fun TypedConfirmDialog(
  title: String,
  message: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  var typed by remember { mutableStateOf("") }
  val confirmable = typed.trim().lowercase() in setOf("y", "yes")
  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        Text(message)
        Spacer(Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.playing_delete_confirm_hint),
          style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
          value = typed,
          onValueChange = { typed = it },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "delete_confirm_input" },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        enabled = confirmable,
        modifier = Modifier.semantics { testTag = "delete_confirm_yes" },
      ) {
        Text(stringResource(R.string.playing_delete_confirm_button))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.playing_delete_confirm_cancel))
      }
    },
  )
}

/**
 * D.24.2 — merged now-playing + queue surface. Single `LazyColumn`,
 * scrollable from cover/transport at the top straight into the queue
 * below.
 *
 * Item layout:
 *  - 0: now-playing card (cover + title + subtitle + scrubber)
 *  - 1: transport row (shuffle / prev / -10 / play / +10 / next /
 *       repeat) — single source of truth for transport
 *  - 2: queue section (divider + "Up next" header + filter +
 *       drag-drop list)
 *
 * The queue-section item self-contains the divider, header, filter,
 * and the drag-reorder list of upcoming items. We keep it in a single
 * `item { ... }` block (rather than `items(...)`) because the drag-
 * reorder helper needs to own its own scroll-axis offset translations
 * and would conflict with `LazyColumn`'s recycler.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun NowPlayingMergedSurface(
  state: PlaybackUiState,
  queueSnapshot: QueueSnapshot,
  listState: LazyListState,
  albumCoversMode: AlbumCoversMode,
  onSeek: (Long) -> Unit,
  onTogglePlayPause: () -> Unit,
  onSeekBackward: () -> Unit,
  onSeekForward: () -> Unit,
  onSeekToPrevious: () -> Unit,
  onSeekToNext: () -> Unit,
  onToggleShuffle: () -> Unit,
  onCycleRepeat: () -> Unit,
  onJumpToQueueIndex: (Int) -> Unit,
  onRemoveQueueItem: (Int) -> Unit,
  onMoveQueueItem: (Int, Int) -> Unit,
  modifier: Modifier = Modifier,
  /** Round 3 — per-track cover override forwarded to the now-playing card. */
  trackCoverUriOverride: String? = null,
) {
  // D.27.4 — measure the surrounding LazyColumn viewport so the queue
  // section can size itself to ≥ one viewport even when the queue has
  // a single item (the user's "can't scroll down one screen length"
  // bug). `BoxWithConstraints` here is bounded by the parent
  // (`Modifier.fillMaxSize()` from the caller) so `maxHeight` is the
  // actual viewport height, not Constraints.Infinity.
  // D.27.8 — while a queue row is being dragged, the outer LazyColumn's
  // own vertical-scroll gesture handler steals the pointer events
  // before `DragReorderColumn`'s `pointerInput` sees them, so the user
  // sees the surface scroll instead of the row lifting. Track drag
  // state and flip `userScrollEnabled` off for the duration of the
  // drag. The `DisposableEffect` cleanup resets the flag if the
  // composable disposes mid-drag (would otherwise leave parent scroll
  // permanently disabled across recompositions in edge cases).
  var isQueueDragging by remember { mutableStateOf(false) }
  DisposableEffect(Unit) {
    onDispose { isQueueDragging = false }
  }
  // Tap-outside-to-dismiss-keyboard. The filter `OutlinedTextField`
  // takes focus + pops the IME; without this, taps anywhere else on
  // the merged surface were ignored and the keyboard stuck around
  // (user feedback). `detectTapGestures` is a non-blocking gesture
  // that doesn't consume scroll, so the LazyColumn's drag still
  // works normally.
  val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  androidx.compose.foundation.layout.BoxWithConstraints(
    modifier = modifier.pointerInput(Unit) {
      detectTapGestures(onTap = {
        focusManager.clearFocus()
        keyboard?.hide()
      })
    },
  ) {
    val viewport = maxHeight
  LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(
      horizontal = 24.dp,
      vertical = 16.dp,
    ),
    userScrollEnabled = !isQueueDragging,
  ) {
    // -- Item 0: now-playing card ------------------------------------
    item(key = "now_playing_card") {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .semantics { testTag = "now_playing_card" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        CoverArt(
          albumId = state.mediaStoreAlbumId,
          size = 96.dp,
          mode = albumCoversMode,
          contentDescription = state.title.ifEmpty { null },
          coverUriOverride = trackCoverUriOverride,
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .semantics { testTag = "now_playing_cover" },
        )
        val noTrackPlaceholder = stringResource(R.string.playing_no_track)
        Text(
          text = state.title.ifEmpty { noTrackPlaceholder },
          style = MaterialTheme.typography.headlineSmall,
          maxLines = 2,
          modifier = Modifier.semantics { testTag = "now_playing_title" },
        )
        Text(
          text = listOfNotNull(
            state.artist.takeIf { it.isNotBlank() },
            state.album.takeIf { it.isNotBlank() },
          ).joinToString(" · ").ifEmpty { "—" },
          style = MaterialTheme.typography.bodyMedium,
        )
        Scrubber(
          positionMs = state.positionMs,
          durationMs = state.durationMs,
          onSeek = onSeek,
        )
      }
    }

    // -- Item 1: transport row ---------------------------------------
    item(key = "transport_row") {
      // R.F.3 — shared with MiniPlayer via PlaybackTransportRow.
      // NowPlaying inserts Replay10 / Forward10 around the play button
      // via the extraStart / extraEnd slots.
      PlaybackTransportRow(
        state = state,
        iconSize = 36.dp,
        playIconSize = 56.dp,
        onTogglePlayPause = onTogglePlayPause,
        onSkipPrevious = onSeekToPrevious,
        onSkipNext = onSeekToNext,
        onToggleShuffle = onToggleShuffle,
        onCycleRepeat = onCycleRepeat,
        testTagPrefix = "now_playing",
        modifier = Modifier.semantics { testTag = "now_playing_transport_row" },
        extraStart = {
          IconButton(onClick = onSeekBackward) {
            Icon(
              Icons.Filled.Replay10,
              contentDescription = stringResource(R.string.playing_cd_seek_back_10),
              modifier = Modifier.size(36.dp),
            )
          }
        },
        extraEnd = {
          IconButton(onClick = onSeekForward) {
            Icon(
              Icons.Filled.Forward10,
              contentDescription = stringResource(R.string.playing_cd_seek_forward_10),
              modifier = Modifier.size(36.dp),
            )
          }
        },
      )
    }

    // -- Item 2: queue section ---------------------------------------
    item(key = "queue_section") {
      // D.26.3: pass a `fillParentMaxHeight` modifier into the queue
      // section's no-match placeholder so a zero-match filter doesn't
      // collapse the LazyColumn content height (which would force the
      // scroll position back to the top of the surface).
      QueueSection(
        snapshot = queueSnapshot,
        onJumpTo = onJumpToQueueIndex,
        onRemove = onRemoveQueueItem,
        onMove = onMoveQueueItem,
        noMatchFillModifier = Modifier.fillParentMaxHeight(),
        parentViewportHeight = viewport,
        onDragStateChange = { isQueueDragging = it },
      )
    }
  }
    // R.A.Q — full-height fast-scroll thumb. User asked for the
    // queue-page indicator back. (Earlier I dropped it because of the
    // "huge hole" oscillation; the math is imperfect on a 3-item
    // LazyColumn with one giant item, but having a position indicator
    // for the queue is more useful than not.)
    com.eight87.tonearmboy.ui.common.FastScrollbar(
      state = listState,
      modifier = Modifier.align(Alignment.CenterEnd),
    )
  }
}

/**
 * D.24.2 — index of the queue-section item inside [NowPlayingMergedSurface].
 * Exposed as a constant so the top-app-bar queue button can
 * `animateScrollToItem` to the right slot, and so tests can assert the
 * scroll target without knowing the LazyColumn internals.
 */
internal const val QUEUE_LIST_INDEX: Int = 2

/**
 * D.22.3 — three rendering modes for [NowPlayingScreen].
 *
 *  - [Connecting]: the Media3 controller hasn't bound yet; show a
 *    spinner + "Connecting to playback…" caption. This is the cold-
 *    start branch that prevents the blank Compose tree.
 *  - [ConnectedEmpty]: the controller is bound but the session has
 *    no queue. We show an "empty card with CTA back to Library" and
 *    auto-pop after 300 ms so the user lands somewhere useful.
 *  - [ConnectedWithMedia]: the existing transport surface, now with
 *    the queue inlined below it (D.24.2).
 */
internal enum class NowPlayingSubState {
  Connecting,
  ConnectedEmpty,
  ConnectedWithMedia,
}

internal fun resolveSubState(state: PlaybackUiState): NowPlayingSubState = when {
  state.connectionPhase == ConnectionPhase.Connecting && !state.hasMedia ->
    NowPlayingSubState.Connecting
  !state.hasMedia -> NowPlayingSubState.ConnectedEmpty
  else -> NowPlayingSubState.ConnectedWithMedia
}

@Composable
private fun NowPlayingConnecting(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.semantics { testTag = "now_playing_connecting" },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    CircularProgressIndicator()
    Text(
      text = stringResource(R.string.playing_connecting),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun NowPlayingEmpty(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LaunchedEffect(Unit) {
    delay(EmptyAutoPopMs)
    onBack()
  }
  Column(
    modifier = modifier.semantics { testTag = "now_playing_empty" },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    Text(
      text = stringResource(R.string.playing_empty_title),
      style = MaterialTheme.typography.headlineSmall,
    )
    Text(
      text = stringResource(R.string.playing_empty_message),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
      onClick = onBack,
      modifier = Modifier.semantics { testTag = "now_playing_empty_back" },
    ) { Text(stringResource(R.string.playing_empty_back_button)) }
  }
}

private const val EmptyAutoPopMs: Long = 300L

@Composable
private fun Scrubber(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
  val total = durationMs.coerceAtLeast(0L)
  val pos = positionMs.coerceIn(0L, total.coerceAtLeast(positionMs))
  var dragValue by remember(positionMs) { mutableStateOf<Float?>(null) }
  val sliderValue = dragValue ?: pos.toFloat()
  val sliderMax = total.toFloat().coerceAtLeast(1f)

  Column {
    Slider(
      value = sliderValue.coerceIn(0f, sliderMax),
      onValueChange = { dragValue = it },
      onValueChangeFinished = {
        dragValue?.let { onSeek(it.toLong()) }
        dragValue = null
      },
      valueRange = 0f..sliderMax,
      modifier = Modifier.fillMaxWidth().semantics { testTag = "now_playing_scrubber" },
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(formatMillis(sliderValue.toLong()), style = MaterialTheme.typography.labelMedium)
      Text(formatMillis(total), style = MaterialTheme.typography.labelMedium)
    }
  }
}

private fun formatMillis(ms: Long): String {
  if (ms <= 0) return "0:00"
  val totalSeconds = ms / 1000
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "%d:%02d".format(minutes, seconds)
}

/**
 * G.2 — Auxio-style swipe-down-from-top to dismiss.
 *
 * Composes with the inner `LazyColumn` via NestedScroll: when the user
 * drags downward past the top of the queue and the LazyColumn refuses
 * the delta (already at offset 0), the unconsumed `available.y` lands
 * here in `onPostScroll`. We accumulate it; if a drag-back-up arrives
 * we drain the accumulator first (matching pull-to-refresh ergonomics).
 * On fling, if the accumulator exceeds [thresholdPx], call [onDismiss].
 *
 * Mid-list scrolls don't trigger this — when the LazyColumn is scrolled
 * mid-content, it consumes downward drags itself, so `available.y` in
 * `onPostScroll` is 0 and the accumulator stays at 0.
 */
// G+ — `pullDownToDismissNestedScroll` removed. Pull-down-from-top is
// now handled by the app-root sheet wrapper in TonearmboyApp; the
// queue's overscroll-down feeds into the shared sheet progress so the
// gesture is one continuous animation, not two parallel ones.
