package com.eight87.tonearmboy.ui.library.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.data.TrackSource
import com.eight87.tonearmboy.data.model.Track
import com.eight87.tonearmboy.ui.library.TileItem
import com.eight87.tonearmboy.ui.library.TrackContextAction
import com.eight87.tonearmboy.ui.library.durationBucket
import com.eight87.tonearmboy.ui.library.initialKey
import com.eight87.tonearmboy.ui.library.sortNameKey
import com.eight87.tonearmboy.ui.library.sortTracks
import com.eight87.tonearmboy.ui.library.yearSectionKey
import com.eight87.tonearmboy.ui.library.yearSectionKeyForEpochSeconds
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode
import com.eight87.tonearmboy.ui.settings.SortKey
import com.eight87.tonearmboy.ui.settings.TabSort
import com.eight87.tonearmboy.ui.settings.ViewMode

@Composable
fun TracksListScreen(
  repository: TrackSource,
  sort: TabSort,
  intelligentSorting: Boolean,
  // D.27.5 — when non-empty, the underlying tracks Flow is filtered.
  filter: com.eight87.tonearmboy.data.FilterCriteria = com.eight87.tonearmboy.data.FilterCriteria(),
  // D.28.3 — list vs tile dispatch. Defaults to `List` so the search
  // screen and other repository-free callers still see the legacy
  // shape without explicit wiring.
  viewMode: ViewMode = ViewMode.List,
  albumCoversMode: AlbumCoversMode = AlbumCoversMode.Balanced,
  onTrackClick: (List<Track>, Int) -> Unit,
  onComingSoon: (String) -> Unit,
  onAddToQueue: (Track) -> Unit = {},
  onAddToPlaylist: (Track) -> Unit = {},
  // D.27.2 — multi-select bulk Add to playlist; null disables the icon.
  onAddTracksToPlaylist: ((List<Long>) -> Unit)? = null,
  onGoToAlbum: (Track) -> Unit = {},
  onGoToArtist: (Track) -> Unit = {},
  // Phase F.2 / F.3 — single-track delete + bulk delete from the
  // multi-select bar. When null (e.g. the search screen), Delete
  // routes through `onComingSoon` instead.
  onDeleteTracks: ((List<Track>) -> Unit)? = null,
  // R1 — when both are present the row gains the 4 cover actions in
  // its overflow menu. `onSearchTrackCover` falls through to the
  // album-level MusicBrainz lookup since track-recording lookups
  // without an MBID are too ambiguous (see album-art-rows.md R1.4).
  onSearchTrackCover: ((Track) -> Unit)? = null,
) {
  val tracks by remember(filter) {
    if (filter.isEmpty()) repository.observeTracks() else repository.tracksMatching(filter)
  }.collectAsState(initial = emptyList())
  TracksListContent(
    tracks = tracks,
    sort = sort,
    intelligentSorting = intelligentSorting,
    viewMode = viewMode,
    albumCoversMode = albumCoversMode,
    onTrackClick = onTrackClick,
    onComingSoon = onComingSoon,
    onAddToQueue = onAddToQueue,
    onAddToPlaylist = onAddToPlaylist,
    onAddTracksToPlaylist = onAddTracksToPlaylist,
    onGoToAlbum = onGoToAlbum,
    onGoToArtist = onGoToArtist,
    onDeleteTracks = onDeleteTracks,
    trackSource = repository,
    onSearchTrackCover = onSearchTrackCover,
  )
}

/**
 * Phase F.2 / F.3 — pure, repository-free body of [TracksListScreen].
 * Pulled out so Compose UI tests can render against a hand-supplied
 * track list without needing to bypass `LibraryRepository`'s on-first-
 * subscription scanner trigger (which wipes a seeded in-memory DB).
 */
@Composable
internal fun TracksListContent(
  tracks: List<Track>,
  sort: TabSort,
  intelligentSorting: Boolean,
  viewMode: ViewMode = ViewMode.List,
  albumCoversMode: AlbumCoversMode = AlbumCoversMode.Balanced,
  onTrackClick: (List<Track>, Int) -> Unit,
  onComingSoon: (String) -> Unit,
  onAddToQueue: (Track) -> Unit = {},
  onAddToPlaylist: (Track) -> Unit = {},
  onAddTracksToPlaylist: ((List<Long>) -> Unit)? = null,
  onGoToAlbum: (Track) -> Unit = {},
  onGoToArtist: (Track) -> Unit = {},
  onDeleteTracks: ((List<Track>) -> Unit)? = null,
  trackSource: TrackSource? = null,
  onSearchTrackCover: ((Track) -> Unit)? = null,
) {
  val sorted = remember(tracks, sort, intelligentSorting) { sortTracks(tracks, sort, intelligentSorting) }
  // R.D.4 — multi-select state hoisted into rememberSelectionState.
  val selection = rememberSelectionState<Long>()
  // T.A.3 — captured for the snackbar message inside `handleTrackAction`,
  // which runs outside Composable scope.
  val deleteComingSoonMessage = stringResource(R.string.library_track_action_delete_coming_soon)

  val onAction: (Track, TrackContextAction) -> Unit = { track, action ->
    val idx = sorted.indexOf(track).coerceAtLeast(0)
    if (action == TrackContextAction.Delete && onDeleteTracks != null) {
      onDeleteTracks(listOf(track))
    } else {
      handleTrackAction(track, sorted, idx, action, onTrackClick, onAddToQueue, onAddToPlaylist, onGoToAlbum, onGoToArtist, onComingSoon, deleteComingSoonMessage)
    }
  }
  val spec = remember(albumCoversMode, onAction, trackSource, onSearchTrackCover) {
    TracksTabSpec(albumCoversMode, onAction, trackSource, onSearchTrackCover)
  }

  // R5 — bulk cover handlers for the multi-select bar's overflow.
  // Each variant calls the per-track repo method for every currently-
  // selected id; we capture the snapshot before clearing so the
  // selection state can collapse immediately.
  val bulkCoverScope = rememberCoroutineScope()
  val bulkCovers: BulkCoverHandlers? = trackSource?.let { src ->
    BulkCoverHandlers(
      onBulkSetEmpty = {
        val ids = selection.snapshot()
        selection.clear()
        bulkCoverScope.launch { ids.forEach { src.clearTrackCoverIntentional(it) } }
      },
      onBulkReset = {
        val ids = selection.snapshot()
        selection.clear()
        bulkCoverScope.launch { ids.forEach { src.resetTrackCover(it) } }
      },
      onBulkSearchOnline = onSearchTrackCover?.let { search ->
        {
          val selectedTracks = sorted.filter { selection.contains(it.id) }
          selection.clear()
          selectedTracks.forEach(search)
        }
      },
    )
  }
  // System back press in selection mode collapses the selection (and
  // dismisses the MultiSelectBar) instead of leaving the screen — this
  // matches Auxio / Files / most M3 contextual-action-bar surfaces.
  // Only enabled while a selection exists, so a back press on an empty
  // selection still routes to the navigator.
  BackHandler(enabled = selection.inSelectionMode) { selection.clear() }
  Column(modifier = Modifier.fillMaxSize().semantics { testTag = "tracks_screen" }) {
    if (selection.inSelectionMode) {
      MultiSelectBar(
        count = selection.size,
        onClose = { selection.clear() },
        onAddToPlaylist = onAddTracksToPlaylist?.let { addMany ->
          {
            // D.27.2 — capture the snapshot ids before clearing the
            // selection so the picker sheet sees the right batch even
            // if the user dismisses without picking and re-enters.
            val ids = selection.snapshot()
            selection.clear()
            addMany(ids)
          }
        },
        onDelete = onDeleteTracks?.let { delete ->
          {
            val selectedTracks = sorted.filter { selection.contains(it.id) }
            selection.clear()
            delete(selectedTracks)
          }
        },
        coverBulk = bulkCovers,
      )
    }
    Box(modifier = Modifier.weight(1f).semantics { testTag = "tracks_list" }) {
      LibraryTabRenderer(
        spec = spec,
        items = sorted,
        sort = sort,
        viewMode = viewMode,
        intelligentSorting = intelligentSorting,
        albumCoversMode = albumCoversMode,
        onItemClick = { track ->
          if (selection.inSelectionMode) selection.toggle(track.id)
          else onTrackClick(sorted, sorted.indexOf(track).coerceAtLeast(0))
        },
        onItemLongClick = { track -> selection.add(track.id) },
        selection = selection,
        // Round 5 — Songs tab passes its TrackSource so tile cells can
        // resolve the per-track cover override; without this, covers
        // fetched by the bulk-art worker stay invisible and the tile
        // keeps rendering the (shared NewPipe) album fallback.
        trackSource = trackSource,
      )
    }
  }
}

/**
 * R.D.3 — TabSpec for the Tracks tab. The most complex of the five:
 * threads multi-select state and a per-row overflow menu through
 * [LibraryTabRenderer.selection]. Constructor captures the
 * [albumCoversMode] (read in tile mode) and the [onAction] callback
 * (invoked by the row's overflow menu).
 */
internal class TracksTabSpec(
  private val albumCoversMode: AlbumCoversMode,
  private val onAction: (Track, TrackContextAction) -> Unit,
  private val trackSource: TrackSource? = null,
  private val onSearchTrackCover: ((Track) -> Unit)? = null,
) : TabSpec<Track> {
  override val testTag: String = "tracks_tab"
  override val emptyMessageRes: Int = com.eight87.tonearmboy.R.string.library_empty_songs
  override val supportsTileMode: Boolean = true

  override fun id(item: Track): Long = item.id

  override fun sectionKey(item: Track, sort: TabSort, intelligentSorting: Boolean): String? =
    when (sort.key) {
      SortKey.Name -> initialKey(sortNameKey(item.title, intelligentSorting))
      SortKey.Artist -> initialKey(sortNameKey(item.artist.orEmpty(), intelligentSorting))
      SortKey.Album -> initialKey(sortNameKey(item.album.orEmpty(), intelligentSorting))
      SortKey.Date -> yearSectionKey(item.year)
      SortKey.DateAdded -> yearSectionKeyForEpochSeconds(item.dateAddedSeconds)
      SortKey.Duration -> durationBucket(item.durationMs)
    }

  override fun toTile(item: Track, resources: android.content.res.Resources): TileItem = TileItem(
    id = item.id,
    title = item.title,
    subtitle = item.artist?.takeIf(String::isNotBlank),
    artUri = null,
    albumArtId = item.mediaStoreAlbumId,
    // Round 5 — flag the tile as a song so CoverArt subscribes to the
    // per-track cover override and renders covers fetched by the bulk
    // worker instead of the (often-shared NewPipe) album fallback.
    trackId = item.id,
  )

  @Composable
  override fun ListRow(
    item: Track,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
  ) {
    TrackRow(
      track = item,
      selected = selected,
      inSelectionMode = inSelectionMode,
      onClick = onClick,
      onLongClick = onLongClick,
      onAction = { action -> onAction(item, action) },
      trackSource = trackSource,
      onSearchTrackCover = onSearchTrackCover,
    )
  }

  // R4 — show the cover-action overflow on tiles only when the
  // tracks tab is feeding from the live repository (so tests with the
  // null trackSource don't render an interactive icon they can't act
  // on).
  override val showTileOverflow: Boolean get() = trackSource != null

  @Composable
  override fun TileOverflowMenu(item: Track, onDismiss: () -> Unit) {
    val src = trackSource ?: return
    val choice = src.trackCoverChoice(item.id)
      .collectAsState(initial = com.eight87.tonearmboy.data.AlbumCoverChoice.NoChoice)
      .value
    val handlers = com.eight87.tonearmboy.ui.library.rememberTrackCoverActions(
      trackSource = src,
      trackId = item.id,
      onSearchOnline = { onSearchTrackCover?.invoke(item) },
    )
    com.eight87.tonearmboy.ui.library.CoverActionsMenuItems(
      choice = choice,
      handlers = handlers,
      onDismiss = onDismiss,
    )
  }
}

private fun handleTrackAction(
  track: Track,
  list: List<Track>,
  index: Int,
  action: TrackContextAction,
  onPlayQueue: (List<Track>, Int) -> Unit,
  onAddToQueue: (Track) -> Unit,
  onAddToPlaylist: (Track) -> Unit,
  onGoToAlbum: (Track) -> Unit,
  onGoToArtist: (Track) -> Unit,
  onComingSoon: (String) -> Unit,
  deleteComingSoonMessage: String,
) {
  when (action) {
    TrackContextAction.Play -> onPlayQueue(list, index)
    TrackContextAction.AddToQueue -> onAddToQueue(track)
    TrackContextAction.AddToPlaylist -> onAddToPlaylist(track)
    TrackContextAction.GoToAlbum -> onGoToAlbum(track)
    TrackContextAction.GoToArtist -> onGoToArtist(track)
    // Phase F (file deletion) wires this; the menu item is `enabled = false`
    // in the meantime so the snackbar stays out of the user's way.
    TrackContextAction.Delete -> onComingSoon(deleteComingSoonMessage)
  }
}

// R.F.1 — TrackContextAction lives in `ui/library/TrackContextAction.kt`,
// shared with `DetailTrackRow`. Overflow menu also shared via TrackContextMenu.

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
  track: Track,
  onClick: () -> Unit,
  onAction: (TrackContextAction) -> Unit,
  selected: Boolean = false,
  inSelectionMode: Boolean = false,
  onLongClick: () -> Unit = {},
  trackSource: TrackSource? = null,
  onSearchTrackCover: ((Track) -> Unit)? = null,
) {
  var menuOpen by remember { mutableStateOf(false) }
  val rowBackground = if (selected) MaterialTheme.colorScheme.secondaryContainer
  else androidx.compose.ui.graphics.Color.Transparent
  // R1 — when the row has a TrackSource handle, render the four
  // cover actions in the overflow menu. Without it the menu falls back
  // to the legacy 6-item shape (e.g. the search screen, which doesn't
  // own track-cover state yet).
  val coverChoice = if (trackSource != null) {
    trackSource.trackCoverChoice(track.id)
      .collectAsState(initial = com.eight87.tonearmboy.data.AlbumCoverChoice.NoChoice)
      .value
  } else null
  val coverHandlers = if (trackSource != null) {
    com.eight87.tonearmboy.ui.library.rememberTrackCoverActions(
      trackSource = trackSource,
      trackId = track.id,
      onSearchOnline = { onSearchTrackCover?.invoke(track) },
    )
  } else null
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(rowBackground)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(horizontal = 16.dp, vertical = 10.dp)
      .semantics {
        testTag = if (selected) "track_row_selected" else "track_row"
      },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
      Text(
        text = listOfNotNull(track.artist?.takeIf { it.isNotBlank() }, track.album?.takeIf { it.isNotBlank() })
          .joinToString(" · ")
          .ifEmpty { stringResource(R.string.library_unknown) },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
      )
    }
    if (!inSelectionMode) Box {
      IconButton(
        onClick = { menuOpen = true },
        modifier = Modifier.semantics { testTag = "track_row_overflow" },
      ) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_cd_more_options)) }
      com.eight87.tonearmboy.ui.library.TrackContextMenu(
        expanded = menuOpen,
        onDismiss = { menuOpen = false },
        onAction = onAction,
        deleteTestTag = "track_context_delete",
        coverChoice = coverChoice,
        coverHandlers = coverHandlers,
      )
    }
  }
  HorizontalDivider()
}
