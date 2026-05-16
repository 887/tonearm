package com.eight87.tonearmboy.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R.C.2 — owns the projection of `MediaController` events into the
 * two Compose-friendly StateFlows ([state] + [queue]).
 *
 * Split out of `PlaybackUiController` to draw a clean line between
 * "what the UI observes" and "what the UI commands". The controller
 * now owns the connection lifecycle + listener attach + the four
 * facets (NowPlayingState/TransportCommands/QueueCommands/
 * ReplayGainCommands); this class owns the read-side projection.
 *
 * The split also enables R.C.2's promised optimisation: the cheap
 * position-only [pushPlaybackState] runs on the 250 ms ticker, the
 * heavier [pushQueueSnapshot] runs only when the listener reports
 * a media-item or timeline change. Pre-split, `pushState()` did
 * both on every tick — the queue's `mediaItemCount`-sized walk +
 * allocation every 250 ms while playing.
 *
 * The projector reads (not stores) [controllerProvider] each time
 * so the controller's own `release()` lifecycle is the source of
 * truth for "is there a controller right now"; same with
 * [connectionPhaseProvider] for handshake status.
 */
@UnstableApi
internal class PlaybackStateProjector(
  private val controllerProvider: () -> MediaController?,
  private val connectionPhaseProvider: () -> ConnectionPhase,
) {
  private val _state = MutableStateFlow(PlaybackUiState.Empty)
  val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

  private val _queue = MutableStateFlow(QueueSnapshot.Empty)
  val queue: StateFlow<QueueSnapshot> = _queue.asStateFlow()

  /**
   * R.C.2 — cheap. Read currentPosition / isPlaying / shuffle /
   * repeat / metadata from the controller and update [state].
   * **Does not touch [queue].** Called by the 250 ms position
   * ticker and by transport commands that don't change the queue
   * (play/pause/seek/shuffle/repeat).
   *
   * When no controller is bound, emits `PlaybackUiState.Empty`
   * with the current handshake phase so the Connecting/Connected
   * UI state still resolves correctly.
   */
  fun pushPlaybackState() {
    val ctl = controllerProvider()
    val phase = connectionPhaseProvider()
    if (ctl == null || ctl.mediaItemCount == 0) {
      _state.value = PlaybackUiState.Empty.copy(connectionPhase = phase)
      return
    }
    val item = ctl.currentMediaItem
    val md = item?.mediaMetadata
    val mediaStoreAlbumId =
      (md?.extras?.getLong(PlaybackUiController.EXTRA_MEDIA_STORE_ALBUM_ID, -1L))
        ?.takeIf { it >= 0 }
    // Round 3 — mediaId carries the domain track id (set by
    // `MediaItemFactory` upstream as `track.id.toString()`).
    val trackId = item?.mediaId?.toLongOrNull()
    _state.value = PlaybackUiState(
      hasMedia = true,
      title = md?.title?.toString().orEmpty(),
      artist = md?.artist?.toString().orEmpty(),
      album = md?.albumTitle?.toString().orEmpty(),
      mediaStoreAlbumId = mediaStoreAlbumId,
      trackId = trackId,
      isPlaying = ctl.isPlaying,
      positionMs = ctl.currentPosition.coerceAtLeast(0),
      durationMs = ctl.duration.takeIf { it > 0 } ?: 0,
      hasNext = ctl.hasNextMediaItem(),
      hasPrevious = ctl.hasPreviousMediaItem(),
      shuffleEnabled = ctl.shuffleModeEnabled,
      repeatMode = ctl.repeatMode,
      connectionPhase = phase,
    )
  }

  /**
   * R.C.2 — heavier. Walks the controller's full media-item list
   * and rebuilds [queue]. **Does not touch [state].** Listener-
   * driven only — call from `onMediaItemTransition`,
   * `onTimelineChanged`, queue-mutation transport commands
   * (add/move/remove), and the initial connect handshake.
   */
  fun pushQueueSnapshot() {
    val ctl = controllerProvider()
    if (ctl == null || ctl.mediaItemCount == 0) {
      _queue.value = QueueSnapshot.Empty
      return
    }
    val items = ArrayList<QueueItem>(ctl.mediaItemCount)
    for (i in 0 until ctl.mediaItemCount) {
      val mi = ctl.getMediaItemAt(i)
      val mmd = mi.mediaMetadata
      val itemAlbumId =
        mmd.extras?.getLong(PlaybackUiController.EXTRA_MEDIA_STORE_ALBUM_ID, -1L)
          ?.takeIf { it >= 0 }
      items += QueueItem(
        mediaId = mi.mediaId,
        title = mmd.title?.toString().orEmpty(),
        artist = mmd.artist?.toString().orEmpty(),
        mediaStoreAlbumId = itemAlbumId,
      )
    }
    _queue.value = QueueSnapshot(items = items, currentIndex = ctl.currentMediaItemIndex)
  }

  /** Convenience: call both. Used at initial connect + after any queue mutation. */
  fun pushAll() {
    pushQueueSnapshot()
    pushPlaybackState()
  }

  /** Reset both flows to empty. Called from controller's `release()`. */
  fun reset() {
    _state.value = PlaybackUiState.Empty
    _queue.value = QueueSnapshot.Empty
  }
}
