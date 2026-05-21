package com.eight87.tonearmboy.ui.playing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.playback.QueueItem
import com.eight87.tonearmboy.playback.QueueSnapshot
import com.eight87.tonearmboy.ui.common.DragReorderColumn

/**
 * D.26.2 — queue rendered as a playlist-style timeline. *Every*
 * controller queue item is shown (indices `0..mediaItemCount - 1`),
 * not just the slice after `currentIndex`. The active row is
 * highlighted in place with `primaryContainer` background, a leading
 * "now playing" speaker icon, and a `bodyLarge`/medium-weight title;
 * other rows render the same as before.
 *
 * Tapping any row calls `onJumpTo(realIndex)` — including tapping the
 * active row, which is a self-seek (no-op visually since the
 * controller is already on that index, but harmless). This means the
 * user can scroll back to a previously-played row and skip "two songs
 * back" without leaving the queue surface.
 *
 * Drag-drop reorder excludes the active row: its handle is disabled
 * and dimmed, and the [onMove] callback clamps `from`/`to` to indices
 * that are not the active one. Media3's behaviour around moving the
 * playing item is awkward, so we keep it pinned in place.
 *
 * Visual-to-controller index translation simplifies to 1:1 when the
 * full queue is visible (no more `currentIdx + 1` offset).
 */
/**
 * One row of the queue list, paired with its 1:1 controller index
 * and an `isActive` flag so taps / removes / drags can forward
 * without re-deriving the index inside the row composable.
 *
 * Hoisted to file scope (was declared inside `QueueSection`) so the
 * memoized `remember { … }` block doesn't have to reach into a
 * composable-local type.
 */
internal data class QueueEntry(val realIndex: Int, val item: QueueItem, val isActive: Boolean)

@OptIn(UnstableApi::class)
@Composable
fun QueueSection(
  snapshot: QueueSnapshot,
  onJumpTo: (Int) -> Unit,
  onRemove: (Int) -> Unit,
  onMove: (from: Int, to: Int) -> Unit,
  modifier: Modifier = Modifier,
  /**
   * D.26.3 — modifier applied to the queue section's outer column so
   * the parent `LazyColumn` keeps a stable content height when the
   * filter narrows to zero matches. The caller (inside a lazy item
   * block) passes `Modifier.fillParentMaxHeight()` so the queue claims
   * at least the viewport's worth of vertical space; without this,
   * a zero-match filter would collapse the section to its empty-state
   * placeholder, shrink the LazyColumn's total content height below
   * the viewport, and force scroll position back to top.
   */
  noMatchFillModifier: Modifier = Modifier,
  /**
   * D.27.4 — parent LazyColumn's viewport height in Dp, supplied by
   * the host's `BoxWithConstraints`. Used to compute the outer
   * column's min-height so even N=1 queues reserve ≥ one viewport
   * worth of scroll room (the user reported "*single-song queue can't
   * scroll down one screenlength*"). Default 0.dp keeps existing
   * tests / call sites working without a viewport hint, in which case
   * the section just sizes itself to N × rowHeight.
   */
  parentViewportHeight: androidx.compose.ui.unit.Dp = 0.dp,
  /**
   * D.27.8 — fires `true` when a queue row is lifted into drag mode,
   * `false` when the drag releases or cancels. Forwarded to the inner
   * [DragReorderColumn]. The host (`NowPlayingMergedSurface`) uses
   * this to flip its outer LazyColumn's `userScrollEnabled` so the
   * vertical drag gesture isn't consumed by parent scrolling. Default
   * null = no plumbing (test call sites and any future non-LazyColumn
   * host don't need it).
   */
  onDragStateChange: ((Boolean) -> Unit)? = null,
) {
  var filter by remember { mutableStateOf("") }
  val filterActive = filter.isNotBlank()

  val items = snapshot.items
  val currentIndex = snapshot.currentIndex

  // D.26.2: render the full queue as a positional timeline. Each
  // visual entry knows its 1:1 controller index so taps / removes /
  // drags forward without offset arithmetic.
  //
  // Memoized on (items, currentIndex) so we don't reallocate the
  // list every recomposition — the controller's queue StateFlow
  // ticks more often than the snapshot identity changes (position
  // updates, etc.) and re-allocating ~hundreds of QueueEntry per
  // tick is real work the user can perceive on slower devices.
  val allEntries: List<QueueEntry> = remember(items, currentIndex) {
    items.mapIndexed { i, qi ->
      QueueEntry(realIndex = i, item = qi, isActive = i == currentIndex)
    }
  }

  val needle = filter.trim().lowercase()
  val visibleEntries: List<QueueEntry> = remember(allEntries, needle, filterActive) {
    if (filterActive) {
      allEntries.filter {
        it.item.title.lowercase().contains(needle) ||
          it.item.artist.lowercase().contains(needle)
      }
    } else allEntries
  }

  // D.30.1 — mini-player → NowPlaying open lag. The unfiltered render
  // path uses `DragReorderColumn`, an eager `Column { forEach }` that
  // composes every QueueRow up-front (it has to, for offset-based drag
  // bookkeeping). With a few hundred queued tracks that meant a 300–
  // 500 ms hitch on the first frame after the user tapped the mini-
  // player. We can't easily virtualize the row composition without
  // losing drag-reorder semantics, so we instead defer the row mount
  // by exactly one frame: the chrome (divider, "Queue" header, filter
  // field) lands immediately on first composition, then a coroutine
  // yields once via `withFrameNanos { }` and flips this flag — the
  // row block (and its hundreds of QueueRow composables) lands on
  // frame N+1, after the perceived "screen open" is already visible.
  //
  // Drag-reorder behaviour is unchanged once mounted. The placeholder
  // reserves the same vertical space (`heightIn(min = N×rowHeight)`,
  // already on the outer Column) so the parent LazyColumn's scroll
  // position stays stable across the row mount.
  var rowsMounted by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    withFrameNanos { /* yield one frame */ }
    rowsMounted = true
  }

  // D.27.4 — `imePadding()` keeps the active row visible when the
  // on-screen keyboard pops up for the filter field. The min-height
  // is computed as `max(parentViewportHeight, N × rowHeight)` so even
  // a single-track queue (N=1, ~56dp) reserves at least one viewport
  // worth of scroll room without forcing a 100-track queue to clip
  // back down to viewport.
  val byRows = (allEntries.size * QUEUE_ROW_HEIGHT_DP).dp
  val outerMin = if (parentViewportHeight > byRows) parentViewportHeight else byRows
  Column(
    modifier = modifier
      .fillMaxWidth()
      .imePadding()
      .heightIn(min = outerMin)
      .semantics { testTag = "queue_section" },
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    HorizontalDivider()
    Text(
      text = stringResource(R.string.playing_queue_header),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.semantics { testTag = "queue_up_next_header" },
    )

    // Borderless surface-container TextField — matches the library
    // search bar pattern and the settings search bar Android uses.
    androidx.compose.material3.TextField(
      value = filter,
      onValueChange = { filter = it },
      singleLine = true,
      leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
      trailingIcon = {
        if (filterActive) {
          IconButton(
            onClick = { filter = "" },
            modifier = Modifier.semantics { testTag = "queue_filter_clear" },
          ) {
            Icon(
              Icons.Filled.Close,
              contentDescription = stringResource(R.string.playing_cd_queue_filter_clear),
            )
          }
        }
      },
      placeholder = { Text(stringResource(R.string.playing_queue_filter_placeholder)) },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      colors = androidx.compose.material3.TextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
      ),
      shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
      modifier = Modifier
        .fillMaxWidth()
        .semantics { testTag = "queue_filter_field" },
    )

    if (allEntries.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.playing_queue_empty),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else if (!rowsMounted) {
      // D.30.1 — first frame after mini-player tap. Rows mount on the
      // next frame; until then, claim the same vertical space the row
      // list would occupy so the parent LazyColumn's content height
      // (and the user's scroll position, if they've returned to the
      // screen) stays stable across the transition.
      Spacer(
        modifier = Modifier
          .fillMaxWidth()
          .height((allEntries.size * QUEUE_ROW_HEIGHT_DP).dp)
          .semantics { testTag = "queue_rows_pending" },
      )
    } else if (visibleEntries.isEmpty()) {
      // D.26.3 — claim the SAME vertical space the unfiltered list
      // would have occupied, so the queue_section item's overall
      // height stays stable when the user types a no-match filter.
      // The earlier fix only used `fillParentMaxHeight()` (passed in
      // as `noMatchFillModifier`), which forced the placeholder to
      // exactly one viewport tall — fine for shallow scroll, broken
      // when the user is scrolled deep into a long queue (N*rowHeight
      // far exceeds the viewport). With `heightIn(min = N*rowHeight)`
      // the placeholder reserves the full unfiltered list height, so
      // the LazyColumn's content height stays stable and scroll
      // position is preserved at any depth. We *don't* compose with
      // `fillParentMaxHeight()` here — that modifier forces an exact
      // size and would clobber the heightIn's larger min constraint.
      // The `noMatchFillModifier` is intentionally ignored in this
      // branch (kept on the API surface for legacy callers / future
      // alternate strategies).
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = (allEntries.size * QUEUE_ROW_HEIGHT_DP).dp)
          .padding(24.dp)
          .semantics { testTag = "queue_no_match_placeholder" },
        // Anchor the message at the top of the reserved space — the
        // box is N×rowHeight tall (potentially several screens) so a
        // centered message would land off-screen for any non-trivial
        // queue size, leaving the user staring at blank space.
        contentAlignment = Alignment.TopCenter,
      ) {
        Text(
          text = stringResource(R.string.playing_queue_no_match),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else if (filterActive) {
      // D.26.2 (filtered): drag is disabled because the visible subset
      // doesn't reflect the underlying controller order. Render rows
      // directly in a Column with their pre-computed real indices.
      Column(modifier = Modifier.fillMaxWidth()) {
        visibleEntries.forEach { e ->
          QueueRow(
            item = e.item,
            isActive = e.isActive,
            dragHandleModifier = Modifier,
            dragHandleEnabled = false,
            onJumpTo = { onJumpTo(e.realIndex) },
            onRemove = { onRemove(e.realIndex) },
          )
          HorizontalDivider()
        }
      }
    } else {
      // D.26.2 (unfiltered): drag-reorder the full queue. The active
      // row participates in the visual list (it stays in place) but
      // its drag handle is dimmed + disabled. The `onMove` clamp in
      // [translateDragMove] additionally guards against the diff
      // helper producing a (from, to) pair that touches the active
      // index — even though the disabled handle should make that
      // unreachable in normal use.
      DragReorderColumn(
        items = allEntries,
        itemKey = { "queue_${it.realIndex}_${it.item.mediaId}" },
        rowHeightDp = QUEUE_ROW_HEIGHT_DP,
        testTagPrefix = "queue",
        onReordered = { reordered ->
          val before = allEntries
          val diff = firstDifference(before, reordered) ?: return@DragReorderColumn
          val (fromVisual, toVisual) = diff
          val clamped = clampMoveAwayFromActive(currentIndex, fromVisual, toVisual)
            ?: return@DragReorderColumn
          val (from, to) = clamped
          onMove(from, to)
        },
        onDragStateChange = onDragStateChange,
      ) { entry, handleModifier ->
        QueueRow(
          item = entry.item,
          isActive = entry.isActive,
          dragHandleModifier = handleModifier,
          dragHandleEnabled = !entry.isActive,
          onJumpTo = { onJumpTo(entry.realIndex) },
          onRemove = { onRemove(entry.realIndex) },
        )
      }
    }
  }
}

@Composable
internal fun QueueRow(
  item: QueueItem,
  isActive: Boolean,
  dragHandleModifier: Modifier,
  dragHandleEnabled: Boolean,
  onJumpTo: () -> Unit,
  onRemove: () -> Unit,
) {
  // Active row reads from `secondaryContainer` so it follows the
  // album-art-derived chrome tint (Theme.kt blends every container
  // tier 40% toward the palette). The fixed QueueActivePurple
  // fallback kicks in only when the secondaryContainer would be
  // close to the inactive surface — i.e. the album palette
  // produced no usable tint — so the active row never blends into
  // the inactive band.
  val activeColor = MaterialTheme.colorScheme.secondaryContainer
  val rowBackground = if (isActive) activeColor else MaterialTheme.colorScheme.surface
  val rowTag = if (isActive) "queue_row_active" else "queue_row"
  // D.27.9.2 — gate the destructive remove behind an M3 AlertDialog.
  // The dialog uses "queue" wording (not "playlist") because the
  // queue is a runtime list, not a saved playlist.
  var showConfirm by remember { mutableStateOf(false) }
  val unknownLabel = stringResource(R.string.playing_unknown)
  val title = item.title.ifEmpty { unknownLabel }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onJumpTo)
      .background(rowBackground)
      .padding(horizontal = 4.dp, vertical = 8.dp)
      .semantics { testTag = rowTag },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // D.27.9.1 — X moves to the leftmost position so it can no longer
    // be tapped by accident when the user is reaching for the drag
    // handle on the right edge.
    IconButton(
      onClick = { showConfirm = true },
      modifier = Modifier
        .padding(horizontal = 4.dp)
        .semantics { testTag = "queue_remove" },
    ) {
      Icon(
        Icons.Filled.Close,
        contentDescription = stringResource(R.string.playing_cd_queue_remove),
      )
    }
    if (isActive) {
      Icon(
        imageVector = Icons.Filled.GraphicEq,
        contentDescription = stringResource(R.string.playing_cd_queue_now_playing),
        modifier = Modifier
          .size(20.dp)
          .padding(end = 4.dp)
          .semantics { testTag = "queue_active_indicator" },
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        style = if (isActive) {
          MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
        } else {
          MaterialTheme.typography.bodyMedium
        },
        maxLines = 1,
      )
      Text(
        text = item.artist.ifEmpty { unknownLabel },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
    Box(
      modifier = Modifier
        .size(40.dp)
        .alpha(if (dragHandleEnabled) 1f else 0.3f)
        .then(if (dragHandleEnabled) dragHandleModifier else Modifier)
        .semantics {
          testTag = if (dragHandleEnabled) "queue_drag_handle" else "queue_drag_handle_disabled"
        },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.Filled.DragHandle,
        contentDescription = stringResource(R.string.playing_cd_queue_reorder),
      )
    }
  }
  if (showConfirm) {
    AlertDialog(
      onDismissRequest = { showConfirm = false },
      title = {
        Text(
          text = stringResource(R.string.playing_queue_remove_dialog_title),
          modifier = Modifier.semantics { testTag = "queue_remove_confirm_dialog" },
        )
      },
      text = { Text(text = stringResource(R.string.playing_queue_remove_dialog_text, title)) },
      confirmButton = {
        TextButton(
          onClick = {
            showConfirm = false
            onRemove()
          },
          modifier = Modifier.semantics { testTag = "queue_remove_confirm_button" },
        ) { Text(stringResource(R.string.playing_queue_remove_confirm)) }
      },
      dismissButton = {
        TextButton(
          onClick = { showConfirm = false },
          modifier = Modifier.semantics { testTag = "queue_remove_cancel_button" },
        ) { Text(stringResource(R.string.playing_queue_remove_cancel)) }
      },
    )
  }
}

// R.D.5 — `firstDifference`, `clampMoveAwayFromActive`,
// `translateVisualToReal` moved to `QueueReorderLogic.kt`.

private const val QUEUE_ROW_HEIGHT_DP = 56

/**
 * Stable purple for the active row in the queue list. Sampled to land
 * close to the M3 dark-scheme `primaryContainer` purple shipped with
 * the v1.0 theme, but pinned so the row stays purple regardless of the
 * user's base-theme seed.
 */
@Suppress("unused")
private val QueueActivePurple = Color(0xFF4A4458) // legacy — replaced by colorScheme.secondaryContainer
