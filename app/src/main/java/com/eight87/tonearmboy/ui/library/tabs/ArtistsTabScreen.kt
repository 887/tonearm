package com.eight87.tonearmboy.ui.library.tabs

import android.content.res.Resources
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.data.AlbumCoverChoice
import com.eight87.tonearmboy.data.ArtistSource
import com.eight87.tonearmboy.data.model.Artist
import com.eight87.tonearmboy.ui.library.CoverActionsMenuItems
import com.eight87.tonearmboy.ui.library.TileItem
import com.eight87.tonearmboy.ui.library.countBucket
import com.eight87.tonearmboy.ui.library.initialKey
import com.eight87.tonearmboy.ui.library.rememberArtistCoverActions
import com.eight87.tonearmboy.ui.library.sortArtists
import com.eight87.tonearmboy.ui.library.sortNameKey
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode
import com.eight87.tonearmboy.ui.settings.SettingsRepository
import com.eight87.tonearmboy.ui.settings.SortKey
import com.eight87.tonearmboy.ui.settings.TabSort
import com.eight87.tonearmboy.ui.settings.ViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * D.28 — Artists tab dispatcher. List = sticky-header rows; Tile =
 * LibraryTileGrid with letter-avatar fallback (no representative
 * cover resolved per artist on this pass).
 */
@Composable
fun ArtistsTabScreen(
  repository: ArtistSource,
  settingsRepository: SettingsRepository,
  sort: TabSort,
  intelligentSorting: Boolean,
  viewMode: ViewMode,
  // D.30.2 — when non-empty the underlying artists Flow is filtered.
  // Note: the global `hideCollaborators` setting only applies to the
  // unfiltered path; custom tabs apply their own predicate.
  filter: com.eight87.tonearmboy.data.FilterCriteria = com.eight87.tonearmboy.data.FilterCriteria(),
  onArtistClick: (Artist) -> Unit = {},
) {
  val artists by remember(filter) {
    if (filter.isEmpty()) repository.observeArtists(settingsRepository.hideCollaborators.flow)
    else repository.artistsMatching(filter)
  }.collectAsState(initial = emptyList())
  val sorted = remember(artists, sort, intelligentSorting) { sortArtists(artists, sort, intelligentSorting) }
  // R3 — pass the live ArtistSource down so the row's overflow menu
  // can mutate the per-artist cover override.
  val spec = remember(repository) { ArtistsTabSpecWithCovers(repository) }
  val selection = rememberSelectionState<Long>()
  BackHandler(enabled = selection.inSelectionMode) { selection.clear() }
  Column(modifier = Modifier.fillMaxSize().semantics { testTag = "artists_screen" }) {
    if (selection.inSelectionMode) {
      MultiSelectBar(
        count = selection.size,
        onClose = { selection.clear() },
        onAddToPlaylist = null,
        onDelete = null,
      )
    }
    Box(modifier = Modifier.weight(1f)) {
      LibraryTabRenderer(
        spec = spec,
        items = sorted,
        sort = sort,
        viewMode = viewMode,
        intelligentSorting = intelligentSorting,
        albumCoversMode = AlbumCoversMode.Default,
        onItemClick = { artist ->
          if (selection.inSelectionMode) selection.toggle(artist.id) else onArtistClick(artist)
        },
        onItemLongClick = { artist -> selection.add(artist.id) },
        selection = selection,
      )
    }
  }
}

/** Pre-D.28 wrapper retained so existing callers / tests still compile. */
@Composable
fun ArtistsListScreen(
  repository: ArtistSource,
  settingsRepository: SettingsRepository,
  sort: TabSort,
  intelligentSorting: Boolean,
  onArtistClick: (Artist) -> Unit = {},
) {
  ArtistsTabScreen(
    repository = repository,
    settingsRepository = settingsRepository,
    sort = sort,
    intelligentSorting = intelligentSorting,
    viewMode = ViewMode.List,
    onArtistClick = onArtistClick,
  )
}

/** R.D.3 — TabSpec for the Artists tab. Pre-R3 singleton form. */
internal object ArtistsTabSpec : TabSpec<Artist> {
  override val testTag: String = "artists_tab"
  override val emptyMessageRes: Int = com.eight87.tonearmboy.R.string.library_empty_artists
  override val supportsTileMode: Boolean = true

  override fun id(item: Artist): Long = item.id

  override fun sectionKey(item: Artist, sort: TabSort, intelligentSorting: Boolean): String? =
    when (sort.key) {
      SortKey.Name, SortKey.Artist -> initialKey(sortNameKey(item.name, intelligentSorting))
      // Artists' Album sort actually sorts by albumCount; Duration by
      // trackCount. Bucket the count so headers track the sort.
      SortKey.Album -> countBucket(item.albumCount)
      SortKey.Duration -> countBucket(item.trackCount)
      // Date / DateAdded fall back to the artist letter — Artist
      // entities don't carry a date column.
      SortKey.Date, SortKey.DateAdded ->
        initialKey(sortNameKey(item.name, intelligentSorting))
    }

  override fun toTile(item: Artist, resources: Resources): TileItem = TileItem(
    id = item.id,
    title = item.name,
    subtitle = formatArtistSubtitle(resources, item.albumCount, item.trackCount),
    artUri = null,
    albumArtId = null,
  )

  @Composable
  override fun ListRow(
    item: Artist,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: (Artist) -> Unit,
    onLongClick: (Artist) -> Unit,
  ) {
    val albums = pluralStringResource(R.plurals.library_artist_detail_albums_count, item.albumCount, item.albumCount)
    val tracks = pluralStringResource(R.plurals.library_artist_detail_tracks_count, item.trackCount, item.trackCount)
    TwoLineRow(
      primary = item.name,
      secondary = "$albums · $tracks",
      onClick = { onClick(item) },
      onLongClick = { onLongClick(item) },
    )
  }
}

/**
 * R3 — variant of [ArtistsTabSpec] that owns an [ArtistSource] handle
 * so list rows can carry a 3-dot overflow menu with the four
 * cover-action items. The non-cover form stays as the singleton above
 * for callers (custom tabs, the contract test) that don't thread
 * cover state through.
 */
internal class ArtistsTabSpecWithCovers(
  private val artistSource: ArtistSource,
) : TabSpec<Artist> {
  override val testTag: String = ArtistsTabSpec.testTag
  override val emptyMessageRes: Int = ArtistsTabSpec.emptyMessageRes
  override val supportsTileMode: Boolean = ArtistsTabSpec.supportsTileMode

  override fun id(item: Artist): Long = ArtistsTabSpec.id(item)

  override fun sectionKey(item: Artist, sort: TabSort, intelligentSorting: Boolean): String? =
    ArtistsTabSpec.sectionKey(item, sort, intelligentSorting)

  override fun toTile(item: Artist, resources: Resources): TileItem? =
    ArtistsTabSpec.toTile(item, resources)

  @Composable
  override fun ListRow(
    item: Artist,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: (Artist) -> Unit,
    onLongClick: (Artist) -> Unit,
  ) {
    val albums = pluralStringResource(R.plurals.library_artist_detail_albums_count, item.albumCount, item.albumCount)
    val tracks = pluralStringResource(R.plurals.library_artist_detail_tracks_count, item.trackCount, item.trackCount)
    if (inSelectionMode) {
      TwoLineRow(
        primary = item.name,
        secondary = "$albums · $tracks",
        onClick = { onClick(item) },
        onLongClick = { onLongClick(item) },
      )
    } else {
      ArtistOverflowRow(
        primary = item.name,
        secondary = "$albums · $tracks",
        artistName = item.name,
        artistSource = artistSource,
        onClick = { onClick(item) },
        onLongClick = { onLongClick(item) },
      )
    }
  }

  // R4 — same cover-action surface in tile mode.
  override val showTileOverflow: Boolean = true

  @Composable
  override fun TileOverflowMenu(item: Artist, onDismiss: () -> Unit) {
    val choice = artistSource.artistCoverChoice(item.name)
      .collectAsState(initial = AlbumCoverChoice.NoChoice)
      .value
    val handlers = rememberArtistCoverActions(
      artistSource = artistSource,
      artistName = item.name,
      onSearchOnline = {},
    ).copy(showSearchOnline = false)
    CoverActionsMenuItems(choice = choice, handlers = handlers, onDismiss = onDismiss)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistOverflowRow(
  primary: String,
  secondary: String,
  artistName: String,
  artistSource: ArtistSource,
  onClick: () -> Unit,
  onLongClick: () -> Unit = {},
) {
  var menuOpen by remember { mutableStateOf(false) }
  val coverChoice by artistSource.artistCoverChoice(artistName)
    .collectAsState(initial = AlbumCoverChoice.NoChoice)
  // R3 — artist-level MusicBrainz lookup not yet implemented (would
  // need `MusicBrainzClient.findArtistId(name)` + a CAA artist
  // portrait endpoint). For now the menu surfaces Replace / Set
  // empty / Reset; "Search MusicBrainz" is hidden until R3.5 lands.
  val handlers = rememberArtistCoverActions(
    artistSource = artistSource,
    artistName = artistName,
    onSearchOnline = {},
  ).copy(showSearchOnline = false)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(horizontal = 16.dp, vertical = 10.dp)
      .semantics { testTag = "artist_list_row" },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(primary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
      Text(secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
    Box {
      IconButton(
        onClick = { menuOpen = true },
        modifier = Modifier.semantics { testTag = "artist_row_overflow" },
      ) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_artist_overflow_cd)) }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        CoverActionsMenuItems(
          choice = coverChoice,
          handlers = handlers,
          onDismiss = { menuOpen = false },
        )
      }
    }
  }
  HorizontalDivider()
}

private fun formatArtistSubtitle(resources: Resources, albumCount: Int, trackCount: Int): String {
  val albums = resources.getQuantityString(R.plurals.library_artist_detail_albums_count, albumCount, albumCount)
  val tracks = resources.getQuantityString(R.plurals.library_artist_detail_tracks_count, trackCount, trackCount)
  return "$albums · $tracks"
}
