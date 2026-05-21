package com.eight87.tonearmboy.ui.playing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.playback.PlaybackUiState
import com.eight87.tonearmboy.ui.library.CoverArt
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode

/**
 * Persistent mini-player. Shown as the slot directly above the bottom
 * navigation bar whenever the controller has media queued.
 *
 * D.26.1 layout — three rows + a time-label strip, total height ≤ 144 dp:
 *  - **Info row** (48-dp art + title + "artist · album" + close-X). The
 *    info row's `clickable` opens NowPlaying — tapping art / title /
 *    subtitle is the expand affordance. The close-X stays tappable in
 *    place to stop playback without expanding.
 *  - **Transport row** — shuffle (left), prev, play-pause, next, repeat
 *    (right). The five buttons share the row width via
 *    `Arrangement.SpaceEvenly`. Long-press on play-pause keeps the
 *    existing custom-bar-action behaviour.
 *  - **Slider row** — full Material 3 [Slider] with `onValueChange`
 *    feeding a local drag value and `onValueChangeFinished` calling
 *    [onSeekTo]. Below the slider, a thin row carries the current and
 *    total time labels.
 *
 * The `mini_player` testTag is attached to the info row (which carries
 * the `clickable`) so tap-to-expand semantics in tests still resolve to
 * a single semantics node with a click action.
 */
@OptIn(ExperimentalFoundationApi::class, UnstableApi::class)
@Composable
fun MiniPlayer(
  state: PlaybackUiState,
  onTogglePlayPause: () -> Unit,
  onClose: () -> Unit,
  onExpand: () -> Unit,
  onSkipNext: () -> Unit = {},
  onSkipPrevious: () -> Unit = {},
  onPlayButtonLongPress: () -> Unit = {},
  onToggleShuffle: () -> Unit = {},
  onCycleRepeat: () -> Unit = {},
  onSeekTo: (Long) -> Unit = {},
  albumCoversMode: AlbumCoversMode = AlbumCoversMode.Balanced,
  /**
   * G+ — sheet-drag forwarder. Each vertical drag delta on the info
   * row arrives here as a Y-pixel offset (negative = swipe up).
   * Default no-op keeps tests / standalone callers working without
   * the app-level sheet plumbing.
   */
  onSheetDragDelta: (Float) -> Unit = {},
  /**
   * G+ — fired when the drag gesture ends (release or cancel). Host
   * uses this to settle the sheet's [Animatable] to its nearest snap
   * (0 or 1) via animateTo.
   */
  onSheetDragSettle: () -> Unit = {},
  /**
   * Round 3 — per-track cover override URI. When non-null this wins
   * over [PlaybackUiState.mediaStoreAlbumId]; lets a track-level
   * pinned or bulk-fetched cover render in the mini-player. Null
   * keeps the legacy album-art fallback path.
   */
  trackCoverUriOverride: String? = null,
) {
  if (!state.hasMedia) return
  // G+ — fillMaxSize so the surfaceContainerHigh background fills the
  // sheet's peek slot end-to-end. We wrap the Column in a Material 3
  // Surface (rather than `Modifier.background(surfaceContainerHigh)`)
  // so `LocalContentColor` propagates to the IconButtons in the
  // transport row — `Modifier.background` paints a colour but does
  // NOT set the content-color CompositionLocal, so the row's icons
  // were inheriting the OUTER `onBackground` colour (~black against
  // a tinted dark surface = invisible). Surface fills max size and
  // takes the same colour, so the no-gap guarantee that drove the
  // original Modifier.background choice still holds.
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
  Column(
    modifier = Modifier.fillMaxSize(),
  ) {
    // -- Info row ------------------------------------------------------
    // testTag `mini_player` lives here (with the clickable for onExpand)
    // so tests that `performClick` on the row dispatch the expand
    // handler. Tapping the play / prev / next buttons or close-X lands
    // on those inner clickables instead.
    //
    // G+ (sheet-drag) — vertical drag deltas are forwarded directly
    // to the host via [onSheetDragDelta] so the host's NowPlaying
    // sheet translates / fades 1:1 with the finger. The mini-player
    // itself stays put (Auxio-style: library + mini-player are the
    // base layer; the sheet rises over them). Tap → onExpand still
    // animates the sheet open programmatically.
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onExpand)
        .pointerInput(Unit) {
          detectVerticalDragGestures(
            onDragEnd = onSheetDragSettle,
            onDragCancel = onSheetDragSettle,
          ) { _, delta -> onSheetDragDelta(delta) }
        }
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .semantics { testTag = "mini_player" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // D.24.1: 48-dp album thumb (down from 56 dp in D.21.1) to make
      // room for the transport row while keeping total height ≤ 96 dp.
      CoverArt(
        albumId = state.mediaStoreAlbumId,
        size = 48.dp,
        mode = albumCoversMode,
        contentDescription = null,
        coverUriOverride = trackCoverUriOverride,
        modifier = Modifier
          .size(48.dp)
          .clip(RoundedCornerShape(6.dp))
          .semantics { testTag = "mini_player_cover" },
      )
      val unknownTitle = stringResource(R.string.playing_unknown)
      val unknownArtist = stringResource(R.string.playing_mini_player_unknown_artist)
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = state.title.ifEmpty { unknownTitle },
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 1,
          modifier = Modifier.semantics { testTag = "mini_player_title" },
        )
        val subtitle = listOfNotNull(
          state.artist.takeIf { it.isNotBlank() },
          state.album.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifEmpty { unknownArtist }
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          modifier = Modifier.semantics { testTag = "mini_player_subtitle" },
        )
      }
      IconButton(
        onClick = onClose,
        modifier = Modifier.semantics { testTag = "mini_player_close" },
      ) {
        Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.playing_cd_stop))
      }
    }

    // R.F.3 — transport row shared with NowPlayingScreen via PlaybackTransportRow.
    PlaybackTransportRow(
      state = state,
      iconSize = 24.dp,
      playIconSize = 24.dp,
      onTogglePlayPause = onTogglePlayPause,
      onSkipPrevious = onSkipPrevious,
      onSkipNext = onSkipNext,
      onToggleShuffle = onToggleShuffle,
      onCycleRepeat = onCycleRepeat,
      testTagPrefix = "mini_player",
      onPlayLongPress = onPlayButtonLongPress,
      modifier = Modifier
        .padding(horizontal = 8.dp)
        .semantics { testTag = "mini_player_transport_row" },
    )

    // -- Thin progress line --------------------------------------------
    // Replaced the draggable Slider + timestamp row (D.26.1) with an
    // Auxio-style 2-dp LinearProgressIndicator pinned to the bottom of
    // the mini-player slot. Seeking from the mini-player is gone; users
    // expand to NowPlaying for the full slider. The buttons remain in
    // the transport row above.
    val total = state.durationMs.coerceAtLeast(0L)
    val pos = state.positionMs.coerceIn(0L, total.coerceAtLeast(state.positionMs))
    val progress = if (total > 0L) (pos.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
      progress = { progress },
      modifier = Modifier
        .fillMaxWidth()
        .height(2.dp)
        .semantics { testTag = "mini_player_progress" },
      color = MaterialTheme.colorScheme.primary,
      trackColor = MaterialTheme.colorScheme.surfaceContainer,
      gapSize = 0.dp,
      drawStopIndicator = {},
    )
  }
  }
}

private fun formatMiniPlayerMillis(ms: Long): String {
  if (ms <= 0) return "0:00"
  val totalSeconds = ms / 1000
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "%d:%02d".format(minutes, seconds)
}
