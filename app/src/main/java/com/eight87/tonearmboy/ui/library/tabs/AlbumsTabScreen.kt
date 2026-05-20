package com.eight87.tonearmboy.ui.library.tabs

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.data.AlbumCoverChoice
import com.eight87.tonearmboy.data.AlbumSource
import com.eight87.tonearmboy.data.albumKey
import com.eight87.tonearmboy.data.model.Album
import com.eight87.tonearmboy.ui.library.CoverActionsMenuItems
import com.eight87.tonearmboy.ui.library.CoverArt
import com.eight87.tonearmboy.ui.library.TileItem
import com.eight87.tonearmboy.ui.library.countBucket
import com.eight87.tonearmboy.ui.library.initialKey
import com.eight87.tonearmboy.ui.library.rememberAlbumCoverActions
import com.eight87.tonearmboy.ui.library.sortAlbums
import com.eight87.tonearmboy.ui.library.sortNameKey
import com.eight87.tonearmboy.ui.library.yearSectionKey
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode
import com.eight87.tonearmboy.ui.settings.SortKey
import com.eight87.tonearmboy.ui.settings.TabSort
import com.eight87.tonearmboy.ui.settings.ViewMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * D.28 — albums tab dispatcher. Thin wrapper around
 * [LibraryTabRenderer] driven by [AlbumsTabSpec].
 */
@Composable
fun AlbumsTabScreen(
  repository: AlbumSource,
  sort: TabSort,
  intelligentSorting: Boolean,
  forceSquare: Boolean,
  albumCoversMode: AlbumCoversMode,
  viewMode: ViewMode,
  // D.30.2 — when non-empty the underlying albums Flow is filtered.
  filter: com.eight87.tonearmboy.data.FilterCriteria = com.eight87.tonearmboy.data.FilterCriteria(),
  onAlbumClick: (Album) -> Unit = {},
) {
  val albums by remember(filter) {
    if (filter.isEmpty()) repository.observeAlbums() else repository.albumsMatching(filter)
  }.collectAsState(initial = emptyList())
  AlbumsTabContent(
    albums = albums,
    sort = sort,
    intelligentSorting = intelligentSorting,
    albumCoversMode = albumCoversMode,
    viewMode = viewMode,
    onAlbumClick = onAlbumClick,
    albumSource = repository,
  )
}

/**
 * D.28.4 / D.28.6 — repository-free body of [AlbumsTabScreen] so the
 * `LibraryAlbumsListViewTest` can render against a hand-built album
 * list without spinning the scanner.
 */
@Composable
internal fun AlbumsTabContent(
  albums: List<Album>,
  sort: TabSort,
  intelligentSorting: Boolean,
  albumCoversMode: AlbumCoversMode,
  viewMode: ViewMode,
  onAlbumClick: (Album) -> Unit = {},
  // R4 — when non-null, list rows gain the 4-action overflow menu.
  // Tests that build content directly leave it null and get the
  // pre-R4 row shape.
  albumSource: AlbumSource? = null,
) {
  val sorted = remember(albums, sort, intelligentSorting) { sortAlbums(albums, sort, intelligentSorting) }
  val spec = remember(albumCoversMode, albumSource) { AlbumsTabSpec(albumCoversMode, albumSource) }
  val selection = rememberSelectionState<Long>()
  // Back press collapses the multi-select instead of leaving the screen.
  BackHandler(enabled = selection.inSelectionMode) { selection.clear() }
  Column(modifier = Modifier.fillMaxSize().semantics { testTag = "albums_screen" }) {
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
        albumCoversMode = albumCoversMode,
        onItemClick = { album ->
          if (selection.inSelectionMode) selection.toggle(album.id) else onAlbumClick(album)
        },
        onItemLongClick = { album -> selection.add(album.id) },
        selection = selection,
      )
    }
  }
}

/**
 * R.D.3 — TabSpec for the Albums tab. Sections by name when sort is
 * by name; tile mode shows cover art via [TileItem.albumArtId].
 */
internal class AlbumsTabSpec(
  private val albumCoversMode: AlbumCoversMode,
  private val albumSource: AlbumSource? = null,
) : TabSpec<Album> {
  override val testTag: String = "albums_tab"
  override val emptyMessageRes: Int = com.eight87.tonearmboy.R.string.library_empty_albums
  override val supportsTileMode: Boolean = true

  override fun id(item: Album): Long = item.id

  override fun sectionKey(item: Album, sort: TabSort, intelligentSorting: Boolean): String? =
    when (sort.key) {
      SortKey.Name, SortKey.Album -> initialKey(sortNameKey(item.name, intelligentSorting))
      SortKey.Artist -> initialKey(sortNameKey(item.artist.orEmpty(), intelligentSorting))
      SortKey.Date -> yearSectionKey(item.year)
      // Albums sort by `Duration` actually sorts by track count (see
      // `sortAlbums`); the bucket reflects that.
      SortKey.Duration -> countBucket(item.trackCount)
      // Albums don't carry a date_added column directly — fall through
      // to the album-name letter so headers are still consistent.
      SortKey.DateAdded -> initialKey(sortNameKey(item.name, intelligentSorting))
    }

  override fun toTile(item: Album, resources: Resources): TileItem = TileItem(
    id = item.id,
    title = item.name,
    subtitle = item.artist ?: resources.getString(R.string.library_unknown_artist),
    artUri = null,
    albumArtId = item.mediaStoreAlbumId,
  )

  @Composable
  override fun ListRow(
    item: Album,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: (Album) -> Unit,
    onLongClick: (Album) -> Unit,
  ) {
    AlbumListRow(
      album = item,
      albumCoversMode = albumCoversMode,
      onClick = { onClick(item) },
      onLongClick = { onLongClick(item) },
      albumSource = albumSource.takeIf { !inSelectionMode },
    )
  }

  // R4 — tile-mode overflow with the four cover actions per album.
  override val showTileOverflow: Boolean get() = albumSource != null

  @Composable
  override fun TileOverflowMenu(item: Album, onDismiss: () -> Unit) {
    val src = albumSource ?: return
    val key = remember(item.name, item.artist) { albumKey(item.name, item.artist) }
    val choice = src.albumCoverChoice(key)
      .collectAsState(initial = AlbumCoverChoice.NoChoice)
      .value
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val handlers = rememberAlbumCoverActions(
      albumSource = src,
      albumKey = key,
      onSearchOnline = {
        scope.launch {
          val settings = com.eight87.tonearmboy.AppGraph
            .get(ctx.applicationContext).settingsRepository
          val service = settings.coverArtService.flow.first()
          val mbScore = settings.coverArtMatchScore.flow.first()
          com.eight87.tonearmboy.data.albumart.AlbumArtFetcher(src).fetch(
            context = ctx,
            albumName = item.name,
            albumArtist = item.artist,
            service = service,
            musicBrainzMinScore = mbScore,
            overwriteUserChoice = true,
          )
        }
      },
    )
    CoverActionsMenuItems(choice = choice, handlers = handlers, onDismiss = onDismiss)
  }
}

/**
 * D.28.4 — list-mode album row: 48 dp leading thumbnail + name + artist.
 *
 * R4 — when [albumSource] is non-null the row gains a trailing 3-dot
 * overflow icon carrying the four cover actions (Replace / Search /
 * Set empty / Reset). The cover-art override is also applied to the
 * leading thumbnail so the row reflects the user's choice immediately.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumListRow(
  album: Album,
  albumCoversMode: AlbumCoversMode,
  onClick: () -> Unit,
  onLongClick: () -> Unit = {},
  albumSource: AlbumSource? = null,
) {
  val coverChoiceKey = remember(album.name, album.artist) { albumKey(album.name, album.artist) }
  val coverChoice = if (albumSource != null) {
    albumSource.albumCoverChoice(coverChoiceKey)
      .collectAsState(initial = AlbumCoverChoice.NoChoice)
      .value
  } else AlbumCoverChoice.NoChoice
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // R4 — track-row pattern: search-online routes through AlbumArtFetcher.
  val handlers = if (albumSource != null) {
    rememberAlbumCoverActions(
      albumSource = albumSource,
      albumKey = coverChoiceKey,
      onSearchOnline = {
        scope.launch {
          val settings = com.eight87.tonearmboy.AppGraph
            .get(context.applicationContext).settingsRepository
          val service = settings.coverArtService.flow.first()
          val mbScore = settings.coverArtMatchScore.flow.first()
          com.eight87.tonearmboy.data.albumart.AlbumArtFetcher(albumSource).fetch(
            context = context,
            albumName = album.name,
            albumArtist = album.artist,
            service = service,
            musicBrainzMinScore = mbScore,
            overwriteUserChoice = true,
          )
        }
      },
    )
  } else null
  val resolvedOverride = (coverChoice as? AlbumCoverChoice.Pinned)?.uri
  val coverAlbumId = if (coverChoice is AlbumCoverChoice.IntentionallyEmpty) null
  else album.mediaStoreAlbumId
  var menuOpen by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(horizontal = 16.dp, vertical = 8.dp)
      .semantics { testTag = "album_list_row" },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CoverArt(
      albumId = coverAlbumId,
      size = 48.dp,
      mode = albumCoversMode,
      contentDescription = album.name,
      coverUriOverride = resolvedOverride,
      albumName = album.name,
      albumArtist = album.artist,
      modifier = Modifier
        .size(48.dp)
        .clip(RoundedCornerShape(6.dp)),
    )
    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
      Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
      Text(
        text = album.artist ?: stringResource(R.string.library_unknown_artist),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
      )
    }
    if (handlers != null) {
      Box {
        IconButton(
          onClick = { menuOpen = true },
          modifier = Modifier.semantics { testTag = "album_row_overflow" },
        ) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_album_overflow_cd)) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          CoverActionsMenuItems(
            choice = coverChoice,
            handlers = handlers,
            onDismiss = { menuOpen = false },
          )
        }
      }
    }
  }
  HorizontalDivider()
}
