package com.eight87.tonearmboy.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.eight87.tonearmboy.data.TrackSource
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode

/**
 * D.28.3 — shared tile renderer for every library tab in tile mode.
 *
 * Each tab maps its domain rows ([Track] / [Album] / [Artist] / [Genre] /
 * [Playlist]) into [TileItem] before handing the list off here, so
 * tile-rendering chrome only lives in one place.
 *
 * The grid is built with `GridCells.Adaptive(minSize = 160.dp)` per the
 * D.28 plan, giving ~2 columns on phone and ~3 on tablet. Each tile is
 * a square cover image (via Coil for an explicit URI, or [CoverArt] for
 * a MediaStore album id) followed by title + optional subtitle.
 *
 * D.28.5 — when [sectionKeys] is non-empty the grid surfaces letter
 * group headers as full-row items (`span = maxLineSpan`) so the
 * alphabet rail can scroll the grid by section the same way it does
 * the list.
 *
 * @param items per-tile data
 * @param sectionKeys parallel list — `sectionKeys[i]` is the section
 *   key (e.g. `"A"`) for `items[i]`. Empty list = no sectioning.
 */
data class TileItem(
  val id: Long,
  val title: String,
  val subtitle: String?,
  /**
   * SAF / network image URI for the cover. When null and
   * [albumArtId] is also null the tile renders the music-note
   * placeholder via [CoverArt].
   */
  val artUri: String?,
  /**
   * MediaStore album id used by [CoverArt] when [artUri] is null.
   * Songs / Albums / Genres / Artists tiles use this; Playlist tiles
   * generally pre-resolve to [artUri] (or the letter avatar fallback).
   */
  val albumArtId: Long?,
  /**
   * Round 5 — when non-null, the tile represents an individual song
   * and [CoverArt] subscribes to the per-track cover override so
   * pinned-per-track URIs (set by the user or by the bulk-art worker)
   * render in place of the album fallback. Only the Songs tab sets
   * this; Albums / Artists / Genres / Playlists leave it null.
   */
  val trackId: Long? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryTileGrid(
  tiles: List<TileItem>,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(8.dp),
  state: LazyGridState = rememberLazyGridState(),
  sectionKeys: List<String> = emptyList(),
  onTileClick: (TileItem) -> Unit = {},
  onTileLongClick: (TileItem) -> Unit = {},
  albumCoversMode: AlbumCoversMode = AlbumCoversMode.Balanced,
  /**
   * Grid layout. Default `Adaptive(160.dp)` matches the pre-G+ shape;
   * `ViewMode.TwoColumn` callers pass `Fixed(2)` for the new
   * pinned-two-columns mode.
   */
  columns: GridCells = GridCells.Adaptive(minSize = 160.dp),
  /**
   * R4 — when non-null, each tile gets a small 24-dp overflow icon
   * pinned to the cover's top-right corner. The slot is invoked
   * `(tile, dismiss) -> @Composable Unit` and is expected to render
   * the menu items the caller wants for that tile (typically
   * [CoverActionsMenuItems]). The dismiss callback closes the menu.
   *
   * Selection mode hides the icon — long-press to multi-select still
   * works and the icon would crowd the checkbox visual.
   */
  tileOverflowMenu: (@Composable (TileItem, () -> Unit) -> Unit)? = null,
  inSelectionMode: Boolean = false,
  /**
   * When non-null, predicate is consulted per-tile to decide whether
   * the tile is selected; matching tiles render a coloured border + a
   * filled-check overlay. Null = no selection rendering (back-compat
   * for tabs that don't expose selection — Albums, Artists, Genres).
   */
  isSelected: ((TileItem) -> Boolean)? = null,
  /**
   * Round 5 — optional TrackSource handle, threaded down to per-tile
   * [CoverArt] so song tiles (whose [TileItem.trackId] is non-null)
   * can subscribe to the per-track cover override and render the
   * cover the bulk-art worker fetched. Null = legacy behaviour
   * (album-cover-only).
   */
  trackSource: TrackSource? = null,
) {
  // Build the (header letter, runStartIndex, runLength) tuples once so
  // both the renderer and the alphabet helpers can reason about the
  // same layout. A group whose key matches the previous item's key
  // collapses into the same run — i.e. the headers appear exactly once
  // per letter group.
  val groups = remember(tiles, sectionKeys) {
    if (sectionKeys.size != tiles.size) emptyList() else buildGroups(sectionKeys)
  }

  LazyVerticalGrid(
    state = state,
    columns = columns,
    contentPadding = contentPadding,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = modifier.semantics { testTag = "library_tile_grid" },
  ) {
    if (groups.isEmpty()) {
      items(items = tiles, key = { it.id }) { tile ->
        TileCell(tile, onTileClick, onTileLongClick, albumCoversMode, tileOverflowMenu, inSelectionMode, isSelected?.invoke(tile) ?: false, trackSource)
      }
    } else {
      groups.forEach { group ->
        item(
          // Include the run-start index in the key so non-contiguous
          // runs of the same section letter (e.g. "M" appearing in
          // two separate runs when sort and sectionKey disagree) get
          // unique LazyGrid keys. Without this, the LazyGrid throws
          // "Key header_M was already used".
          span = { GridItemSpan(maxLineSpan) },
          key = "header_${group.letter}_${group.start}",
        ) {
          TileSectionHeader(group.letter)
        }
        val slice = tiles.subList(group.start, group.start + group.length)
        items(items = slice, key = { it.id }) { tile ->
          TileCell(tile, onTileClick, onTileLongClick, albumCoversMode, tileOverflowMenu, inSelectionMode, isSelected?.invoke(tile) ?: false, trackSource)
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileCell(
  item: TileItem,
  onClick: (TileItem) -> Unit,
  onLongClick: (TileItem) -> Unit,
  albumCoversMode: AlbumCoversMode,
  overflowMenu: (@Composable (TileItem, () -> Unit) -> Unit)? = null,
  inSelectionMode: Boolean = false,
  selected: Boolean = false,
  trackSource: TrackSource? = null,
) {
  var menuOpen by remember { mutableStateOf(false) }
  // Selection visual: dim the cover slightly + paint a 3-dp primary
  // border + drop a filled check badge in the top-right. List rows use
  // a `secondaryContainer` background tint, which doesn't translate to
  // the cover-art tile (the cover fills the box), so the border + badge
  // do the same job here.
  val selectedBorder = MaterialTheme.colorScheme.primary
  val selectedBadgeBg = MaterialTheme.colorScheme.primary
  val selectedBadgeFg = MaterialTheme.colorScheme.onPrimary
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(4.dp)
      .combinedClickable(
        onClick = { onClick(item) },
        onLongClick = { onLongClick(item) },
      )
      .semantics {
        testTag = if (selected) "library_tile_selected_${item.id}" else "library_tile_${item.id}"
      },
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .then(
          if (selected) Modifier.border(3.dp, selectedBorder, RoundedCornerShape(12.dp))
          else Modifier
        ),
      contentAlignment = Alignment.Center,
    ) {
      val coverAlpha = if (selected) 0.55f else 1f
      when {
        item.artUri != null -> AsyncImage(
          model = item.artUri,
          contentDescription = item.title,
          modifier = Modifier.fillMaxSize().alpha(coverAlpha),
          contentScale = ContentScale.Crop,
        )
        item.albumArtId != null -> CoverArt(
          albumId = item.albumArtId,
          size = 160.dp,
          mode = albumCoversMode,
          contentDescription = item.title,
          // The tile's title field IS the album name on the Albums
          // tab; the subtitle field carries the artist. Threading
          // both through lights up the fetch-progress indicator
          // when an `AlbumArtFetcher.fetch` is in flight for this
          // album (manual "Search online" overflow + bulk worker).
          // Tracks-tab tiles also pass the track title as `title`,
          // which is wrong-but-harmless: the album-key derivation
          // just won't match any in-flight fetch (which would only
          // ever fire for an album lookup anyway).
          albumName = item.title,
          albumArtist = item.subtitle,
          // Round 5 — Songs tiles set TileItem.trackId so per-track
          // covers fetched by the bulk worker render here instead of
          // the album fallback (most NewPipe downloads share one
          // synthetic album, so the album fallback was misleading).
          trackId = item.trackId,
          trackSource = trackSource,
          modifier = Modifier.fillMaxSize().alpha(coverAlpha),
        )
        else -> Text(
          text = letterFor(item.title),
          style = MaterialTheme.typography.headlineLarge,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.alpha(coverAlpha),
        )
      }
      if (selected) {
        Box(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(selectedBadgeBg)
            .semantics { testTag = "library_tile_check_${item.id}" },
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = selectedBadgeFg,
            modifier = Modifier.size(18.dp),
          )
        }
      }
      // R4 — anchor the overflow icon + menu inside the cover Box so
      // it sits on top of the artwork without affecting the title row
      // layout below. Selection mode hides it (the long-press
      // selection gesture takes precedence).
      if (overflowMenu != null && !inSelectionMode) {
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
          IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.semantics { testTag = "library_tile_overflow_${item.id}" },
          ) {
            Icon(
              Icons.Filled.MoreVert,
              contentDescription = stringResource(com.eight87.tonearmboy.R.string.library_cd_more_options),
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            overflowMenu(item, { menuOpen = false })
          }
        }
      }
    }
    Text(
      text = item.title,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
    )
    if (item.subtitle != null) {
      Text(
        text = item.subtitle,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
      )
    }
  }
}

@Composable
private fun TileSectionHeader(letter: String) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceContainerHighest)
      .padding(horizontal = 16.dp, vertical = 6.dp)
      .semantics { testTag = "library_tile_section_${letter}" },
  ) {
    Text(text = letter, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
  }
}

/** Internal grouping representation; visible for tests. */
internal data class TileGroup(val letter: String, val start: Int, val length: Int)

/**
 * Walk a parallel list of section keys and produce one [TileGroup] per
 * contiguous run of equal keys. Empty input yields empty output.
 *
 * Visible for `AlphabetScrollerTileTest`.
 */
internal fun buildGroups(keys: List<String>): List<TileGroup> {
  if (keys.isEmpty()) return emptyList()
  val out = mutableListOf<TileGroup>()
  var runStart = 0
  for (i in 1..keys.size) {
    if (i == keys.size || keys[i] != keys[runStart]) {
      out += TileGroup(letter = keys[runStart], start = runStart, length = i - runStart)
      runStart = i
    }
  }
  return out
}

/**
 * D.28.5 — convert a section letter into the *grid* index of the
 * header cell for that letter. Tile mode prepends a full-row header
 * before each run, so the grid indices look like:
 *
 *   0: header A
 *   1..k: tiles starting with A
 *   k+1: header B
 *   …
 *
 * Returns -1 when the letter has no matching group.
 *
 * Pure / public so `AlphabetScrollerTileTest` can pin the math without
 * standing up a Compose host.
 */
internal fun tileIndexForGroups(groups: List<TileGroup>, letter: String): Int {
  var idx = 0
  for (g in groups) {
    if (g.letter == letter) return idx
    idx += 1 // header
    idx += g.length
  }
  return -1
}

/** Convenience overload working straight from a flat keys list. */
fun tileIndexFor(sectionKeys: List<String>, letter: String): Int =
  tileIndexForGroups(buildGroups(sectionKeys), letter)

/**
 * R.A.Q — inverse of [tileIndexFor]: which section letter contains
 * the tile at [gridIndex]? Used by the FastScrollbar bubble.
 */
fun letterForTileIndex(sectionKeys: List<String>, gridIndex: Int): String? {
  val groups = buildGroups(sectionKeys)
  var idx = 0
  for (g in groups) {
    val sectionEnd = idx + 1 + g.length
    if (gridIndex < sectionEnd) return g.letter
    idx = sectionEnd
  }
  return groups.lastOrNull()?.letter
}
