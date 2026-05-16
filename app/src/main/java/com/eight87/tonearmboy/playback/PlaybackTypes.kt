package com.eight87.tonearmboy.playback

import androidx.media3.common.Player

/** Snapshot of the parts of `MediaController` state the UI cares about. */
data class PlaybackUiState(
  val hasMedia: Boolean,
  val title: String,
  val artist: String,
  val album: String,
  /**
   * D.15.7 — MediaStore album id of the playing track, when the source
   * surface attached one. Used by `NowPlayingScreen` to drive `CoverArt`.
   */
  val mediaStoreAlbumId: Long? = null,
  /**
   * Round 3 — domain track id (the same `track.id` we stash in
   * MediaItem.mediaId). Used by NowPlaying / MiniPlayer to subscribe
   * to the per-track cover-override Flow so the user's pinned or
   * bulk-fetched per-song cover wins over the album fallback.
   * Null when no media is bound or the mediaId can't be parsed as a
   * Long (legacy MediaItems).
   */
  val trackId: Long? = null,
  val isPlaying: Boolean,
  val positionMs: Long,
  val durationMs: Long,
  val hasNext: Boolean,
  val hasPrevious: Boolean,
  /**
   * D.21.4 — controller-driven shuffle / repeat state, surfaced so the
   * queue header + NowPlaying transport row can render their toggle
   * buttons without polling the controller from the UI thread.
   */
  val shuffleEnabled: Boolean = false,
  /**
   * One of [Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ALL],
   * [Player.REPEAT_MODE_ONE].
   */
  val repeatMode: Int = Player.REPEAT_MODE_OFF,
  /**
   * D.22.3 — coarse handshake phase. `Connecting` is the brief window
   * between activity start and Media3 binding the in-process
   * `MediaController` to the running `PlaybackService`; `Connected`
   * means the controller is alive and the rest of the state fields
   * reflect actual session state. NowPlayingScreen renders three
   * distinct sub-states keyed off `connectionPhase` + `hasMedia`.
   */
  val connectionPhase: ConnectionPhase = ConnectionPhase.Connecting,
) {
  companion object {
    val Empty = PlaybackUiState(
      hasMedia = false,
      title = "",
      artist = "",
      album = "",
      mediaStoreAlbumId = null,
      trackId = null,
      isPlaying = false,
      positionMs = 0,
      durationMs = 0,
      hasNext = false,
      hasPrevious = false,
      shuffleEnabled = false,
      repeatMode = Player.REPEAT_MODE_OFF,
      connectionPhase = ConnectionPhase.Connecting,
    )
  }
}

/**
 * D.22.3 — handshake phase between the UI tree and the
 * `PlaybackService`. The activity's `LaunchedEffect(Unit) {
 * playback.connect() }` is async; the first emission of
 * [PlaybackUiController.state] arrives before that future resolves,
 * which is the cold-start "blank Compose tree" frame race that this
 * enum exists to flag.
 */
enum class ConnectionPhase { Connecting, Connected }

/**
 * D.15.5 — one queue entry, denormalized from a Media3 `MediaItem`
 * into the fields the queue sheet renders. [mediaId] is the same
 * `track.id.toString()` we feed into the controller, so callers can
 * round-trip back to the Track domain object via the library cache
 * if they need to.
 */
data class QueueItem(
  val mediaId: String,
  val title: String,
  val artist: String,
  /**
   * D.21.2 — MediaStore album id of the queued track, when the source
   * surface attached one as an extra. Used by `QueueSheet`'s pinned
   * active-track header to render real cover art via `CoverArt`.
   */
  val mediaStoreAlbumId: Long? = null,
)

data class QueueSnapshot(
  val items: List<QueueItem>,
  val currentIndex: Int,
) {
  companion object {
    val Empty = QueueSnapshot(emptyList(), -1)
  }
}
