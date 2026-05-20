package com.eight87.tonearmboy.ui.library.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.data.PlaylistStore
import com.eight87.tonearmboy.data.model.Playlist
import com.eight87.tonearmboy.ui.library.NewPlaylistSheet
import com.eight87.tonearmboy.ui.library.PlaylistsTilesScreen
import com.eight87.tonearmboy.ui.library.TileItem
import com.eight87.tonearmboy.ui.library.initialKey
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode
import com.eight87.tonearmboy.ui.settings.TabSort
import com.eight87.tonearmboy.ui.settings.ViewMode
import kotlinx.coroutines.launch

/**
 * D.28 — Playlists tab dispatcher. Tile mode → the existing
 * [PlaylistsTilesScreen] (D.27.6) which has its own rename/delete/
 * set-cover affordances; List mode → [LibraryTabRenderer] driven by
 * [PlaylistsTabSpec].
 *
 * The "New playlist" FAB is rendered at this level so it shows in
 * all three view modes (List / Tile / TwoColumn). PlaylistsTilesScreen
 * is told to skip its own FAB via `showFab = false`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsTabScreen(
  repository: PlaylistStore,
  viewMode: ViewMode,
  onPlaylistClick: (Long) -> Unit,
  onRenamePlaylist: (Long, String) -> Unit = { _, _ -> },
  onDeletePlaylist: (Long) -> Unit = {},
  onSetPlaylistCover: (Long, String?) -> Unit = { _, _ -> },
) {
  val scope = rememberCoroutineScope()
  var showCreate by remember { mutableStateOf(false) }
  // List-mode selection. Tile-mode selection is owned by
  // PlaylistsTilesScreen via its own bespoke grid; the user can still
  // long-press tiles there but the unified selection state lives here
  // for the list path. Followup will lift this into the tile path too.
  val selection = rememberSelectionState<Long>()
  BackHandler(enabled = selection.inSelectionMode) { selection.clear() }

  Column(modifier = Modifier.fillMaxSize()) {
    if (selection.inSelectionMode) {
      MultiSelectBar(
        count = selection.size,
        onClose = { selection.clear() },
        onAddToPlaylist = null,
        onDelete = null,
      )
    }
    Box(modifier = Modifier.fillMaxSize()) {
      if (viewMode == ViewMode.Tile || viewMode == ViewMode.TwoColumn) {
        PlaylistsTilesScreen(
          repository = repository,
          onPlaylistClick = onPlaylistClick,
          onRenamePlaylist = onRenamePlaylist,
          onDeletePlaylist = onDeletePlaylist,
          onSetPlaylistCover = onSetPlaylistCover,
          twoColumn = viewMode == ViewMode.TwoColumn,
          showFab = false,
          selection = selection,
        )
      } else {
        val playlists by repository.observePlaylists().collectAsState(initial = emptyList())
        LibraryTabRenderer(
          spec = PlaylistsTabSpec,
          items = playlists,
          sort = TabSort.Default,
          viewMode = ViewMode.List,
          intelligentSorting = false,
          albumCoversMode = AlbumCoversMode.Default,
          onItemClick = { p ->
            if (selection.inSelectionMode) selection.toggle(p.id)
            else onPlaylistClick(p.id)
          },
          onItemLongClick = { p -> selection.add(p.id) },
          selection = selection,
        )
      }
      ExtendedFloatingActionButton(
        onClick = { showCreate = true },
        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        text = { Text(stringResource(R.string.playlist_tiles_new_fab)) },
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).semantics { testTag = "new_playlist_fab" },
      )
    }
  }

  if (showCreate) {
    NewPlaylistSheet(
      onDismiss = { showCreate = false },
      onCreate = { name ->
        scope.launch { repository.createPlaylist(name) }
        showCreate = false
      },
    )
  }
}

/** Pre-D.28 wrapper retained for the existing `PlaylistsListScreenTest` and callers. */
@Composable
fun PlaylistsListScreen(
  repository: PlaylistStore,
  onPlaylistClick: (Long) -> Unit,
  onRenamePlaylist: (Long, String) -> Unit = { _, _ -> },
  onDeletePlaylist: (Long) -> Unit = {},
  onSetPlaylistCover: (Long, String?) -> Unit = { _, _ -> },
) {
  PlaylistsTilesScreen(
    repository = repository,
    onPlaylistClick = onPlaylistClick,
    onRenamePlaylist = onRenamePlaylist,
    onDeletePlaylist = onDeletePlaylist,
    onSetPlaylistCover = onSetPlaylistCover,
  )
}

/**
 * R.D.3 — TabSpec for the Playlists tab. List-only inside this spec;
 * tile mode is dispatched to [PlaylistsTilesScreen] before the
 * renderer is reached.
 */
internal object PlaylistsTabSpec : TabSpec<Playlist> {
  override val testTag: String = "playlists_tab"
  override val emptyMessageRes: Int = com.eight87.tonearmboy.R.string.library_empty_playlists
  override val supportsTileMode: Boolean = false

  override fun id(item: Playlist): Long = item.id

  override fun sectionKey(item: Playlist, sort: TabSort, intelligentSorting: Boolean): String? =
    initialKey(item.name.uppercase())

  override fun toTile(item: Playlist, resources: android.content.res.Resources): TileItem? = null

  @Composable
  override fun ListRow(
    item: Playlist,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: (Playlist) -> Unit,
    onLongClick: (Playlist) -> Unit,
  ) {
    TwoLineRow(
      primary = item.name,
      secondary = pluralStringResource(R.plurals.library_playlist_subtitle_tracks, item.trackCount, item.trackCount),
      onClick = { onClick(item) },
      onLongClick = { onLongClick(item) },
    )
  }
}
