package com.eight87.tonearmboy.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.eight87.tonearmboy.ui.nav.LocalSectionTitle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.data.AlbumSource
import com.eight87.tonearmboy.data.ArtistSource
import com.eight87.tonearmboy.data.CustomTabStore
import com.eight87.tonearmboy.data.GenreSource
import com.eight87.tonearmboy.data.LibraryRepository
import com.eight87.tonearmboy.data.PlaylistStore
import com.eight87.tonearmboy.data.TrackSource
import com.eight87.tonearmboy.data.model.Album
import com.eight87.tonearmboy.data.model.Artist
import com.eight87.tonearmboy.data.model.Genre
import com.eight87.tonearmboy.data.model.Track
import com.eight87.tonearmboy.ui.common.FastScrollbar
import com.eight87.tonearmboy.ui.library.tabs.AlbumsTabScreen
import com.eight87.tonearmboy.ui.library.tabs.ArtistsTabScreen
import com.eight87.tonearmboy.ui.library.tabs.EmptyState
import com.eight87.tonearmboy.ui.library.tabs.GenresTabScreen
import com.eight87.tonearmboy.ui.library.tabs.MultiSelectBar
import com.eight87.tonearmboy.ui.library.tabs.PlaylistsTabScreen
import com.eight87.tonearmboy.ui.library.tabs.SectionHeader
import com.eight87.tonearmboy.ui.library.tabs.TracksListScreen
import com.eight87.tonearmboy.ui.library.tabs.TwoLineRow
import com.eight87.tonearmboy.ui.settings.LibraryTab
import com.eight87.tonearmboy.ui.settings.SettingsRepository
import com.eight87.tonearmboy.ui.settings.SortDirection
import com.eight87.tonearmboy.ui.settings.SortKey
import com.eight87.tonearmboy.ui.settings.TabSort
import com.eight87.tonearmboy.ui.settings.ViewMode
import com.eight87.tonearmboy.ui.settings.catalog.SettingsDimens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// D.16.1 — shared chrome contract for library lists / grids / details.
//
// Every per-row composable (`TrackRow`, `ArtistRow`, …) accepts a
// `containerStyle` so callers can decide whether the row sits inside a
// settings-style card or runs edge-to-edge. The default is the M3
// Expressive grouped card; the EdgeToEdge variant is preserved for
// places where the card chrome would fight the surrounding surface
// (e.g. the album-detail tracks list lives inside its own card already).
//
// See the M3 Expressive note in CLAUDE.md ("M3 Expressive layout") for
// the larger discussion of insets and the boundary between the rail
// and the content area.
enum class ContainerStyle { SettingsCard, EdgeToEdge }

/**
 * D.16.1 — modifier that gives a list/grid the M3 Expressive grouped-
 * card look: rounded corners, the same `surfaceContainer` background
 * that [com.eight87.tonearmboy.ui.settings.catalog.SettingsCard] uses, and
 * the canonical 16 dp horizontal page padding that makes the chrome
 * "sit in the middle" rather than running edge-to-edge.
 *
 * We ship this as a Modifier (rather than a wrapping Card composable)
 * because library content is a `LazyColumn` / `LazyVerticalGrid`
 * — placing those inside a `Card { Column { … } }` would force them
 * into bounded height, breaking scroll. Same visual, lighter chrome.
 */
@Composable
internal fun Modifier.libraryListCard(): Modifier {
  // Whisperboy-style edge-to-edge: the list fills the full width with
  // no left/right page padding and no wrapping card chrome — items
  // (which carry their own surfaceContainer* tier) read directly
  // against the page surface (or the album-art background, when that
  // layer is active). Keeps section headers + tiles full-width and
  // lets the cover-as-wallpaper bleed past the edges of every row.
  return this.semantics { testTag = "library_list_card" }
}

/**
 * The library is the **root** destination. It holds the top app bar
 * ("tonearmboy" title + search / sort / overflow icons), the
 * `PrimaryTabRow` for {Songs, Albums, Artists, Genres, Playlists}, and
 * the per-tab content. Per-tab sort state is read from
 * [SettingsRepository] and persisted on confirm.
 *
 * Tab visibility / order comes from [SettingsRepository.snapshot]; tabs
 * marked hidden in Personalize are filtered out at the strip layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  // R.A.4 — composition root for the Library destination, so it takes
  // every narrow interface its sub-tabs need (ISP at the dispatch layer
  // means more constructor params here, but each downstream screen
  // depends on only the slice it reads). Keep them in the same order
  // as the eight-interface declaration in `data/LibraryDataInterfaces`.
  tracks: TrackSource,
  albums: AlbumSource,
  artists: ArtistSource,
  genres: GenreSource,
  playlists: PlaylistStore,
  customTabs: CustomTabStore,
  scanner: com.eight87.tonearmboy.data.LibraryScanner,
  settingsRepository: SettingsRepository,
  onTrackClick: (List<Track>, Int) -> Unit,
  onPlaylistClick: (Long) -> Unit,
  onOpenSearch: () -> Unit,
  // D.16.3 — top-right wheel goes directly to Settings root.
  onOpenSettings: () -> Unit,
  // D.16.2 — bottom-left rail gear goes to the Library-tabs config
  // (Settings → Personalize). Two distinct entry points to the Settings
  // *system*, keyed to two different intents.
  onOpenLibraryTabsConfig: () -> Unit,
  onComingSoon: (String) -> Unit,
  snackbarHostState: SnackbarHostState,
  // D.15: navigation hooks for the new detail screens + track-row overflow.
  onOpenAlbum: (name: String, albumArtist: String?) -> Unit,
  onOpenArtist: (name: String) -> Unit,
  onOpenGenre: (name: String) -> Unit,
  onAddToQueue: (Track) -> Unit,
  onAddToPlaylist: (Track) -> Unit,
  /**
   * D.27.2 — bulk Add-to-playlist from the multi-select contextual bar.
   * Receives the selected track ids; the app-root resolves them against
   * the current library and opens [PlaylistPickerSheet] for the batch.
   */
  onAddTracksToPlaylist: (List<Long>) -> Unit,
  onRenamePlaylist: (Long, String) -> Unit,
  onDeletePlaylist: (Long) -> Unit,
  // D.27.6 — set the playlist's cover URI (or clear it).
  onSetPlaylistCover: (Long, String?) -> Unit,
  // D.27.6 — open the track-picker for the playlist (used from the
  // playlist tile context menu).
  onOpenPlaylistDetail: (Long) -> Unit = {},
  // D.27.5 — current library filter + setter, owned by the app root so
  // the badge / sheet survive across tab changes.
  filter: com.eight87.tonearmboy.data.FilterCriteria,
  onFilterChange: (com.eight87.tonearmboy.data.FilterCriteria) -> Unit,
  // Phase F.2 / F.3 — file-deletion entry. Passed list lets the same
  // callback serve the single-track menu item and the multi-select bar.
  onDeleteTracks: (List<Track>) -> Unit,
) {
  // R.B.5 — narrow per-key Flow reads via the facets the repository implements.
  val libraryTabsList by settingsRepository.libraryTabs.flow
    .collectAsState(initial = LibraryTab.DefaultOrder)
  val intelligentSorting by settingsRepository.intelligentSorting.flow
    .collectAsState(initial = true)
  val albumCoversMode by settingsRepository.albumCoversMode.flow
    .collectAsState(initial = com.eight87.tonearmboy.ui.settings.AlbumCoversMode.Default)
  val forceSquareCovers by settingsRepository.forceSquareCovers.flow
    .collectAsState(initial = false)
  // Visible tabs: anything in libraryTabsList is "shown" (hidden tabs
  // already filtered by SettingsRepository's parser when wired to the
  // Personalize sub-page). Default order = canonical.
  val visibleTabs = remember(libraryTabsList) {
    libraryTabsList.ifEmpty { LibraryTab.DefaultOrder }
  }
  // D.18.5 — custom tabs are rendered after the built-ins in the rail.
  val customTabsList by customTabs.customTabs().collectAsState(initial = emptyList())
  val totalRailCount = visibleTabs.size + customTabsList.size
  var selectedIndex by rememberSaveable { mutableStateOf(0) }
  if (totalRailCount == 0) {
    selectedIndex = 0
  } else if (selectedIndex >= totalRailCount) {
    selectedIndex = 0
  }
  val isCustomSelected = selectedIndex >= visibleTabs.size
  val activeTab = if (!isCustomSelected && visibleTabs.isNotEmpty()) visibleTabs[selectedIndex] else LibraryTab.Songs
  val activeCustomTab = if (isCustomSelected) customTabsList.getOrNull(selectedIndex - visibleTabs.size) else null

  val activeSort by settingsRepository.tabSort(activeTab).collectAsState(initial = TabSort.Default)
  // D.28.1 — read every tab's persisted view mode in one Flow so the
  // toggle is per-tab and switching tabs reads that tab's saved mode.
  val viewModes by settingsRepository.viewModes.collectAsState(
    initial = LibraryTab.entries.associateWith { ViewMode.defaultFor(it) },
  )
  // Custom tabs persist their view mode in DataStore keyed by tab id —
  // independent from the built-in `viewModes` map. When `activeCustomTab`
  // changes we re-subscribe so the icon and content reflect the new tab.
  val customTabViewModeDefault = activeCustomTab?.let { defaultViewModeFor(it.contentType) } ?: ViewMode.List
  val customTabViewMode by produceState(
    initialValue = customTabViewModeDefault,
    key1 = activeCustomTab?.id,
  ) {
    val tab = activeCustomTab
    if (tab != null) {
      val default = defaultViewModeFor(tab.contentType)
      settingsRepository.customTabViewMode(tab.id, default).collect { value = it }
    }
  }
  val activeViewMode = if (activeCustomTab != null) customTabViewMode
    else viewModes[activeTab] ?: ViewMode.defaultFor(activeTab)
  val scope = rememberCoroutineScope()

  var showSortSheet by remember { mutableStateOf(false) }
  // D.27.5 — filter sheet visibility. The filter state itself is owned
  // by the app root so it survives tab changes and screen pushes.
  var showFilterSheet by remember { mutableStateOf(false) }

  // Drive the dynamic top-bar title for the active tab.
  val sectionTitle = LocalSectionTitle.current
  // Resolve resource ids outside the LaunchedEffect so we don't have to
  // hold a Context reference inside the suspend block.
  val context = LocalContext.current
  LaunchedEffect(activeTab, activeCustomTab) {
    sectionTitle.value = if (activeCustomTab != null) {
      activeCustomTab.name
    } else {
      context.getString(tabLabelRes(activeTab))
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = sectionTitle.value,
            modifier = Modifier.semantics { testTag = "library_title" },
          )
        },
        actions = {
          IconButton(onClick = onOpenSearch, modifier = Modifier.semantics { testTag = "topbar_search" }) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.library_cd_search))
          }
          // The sort-aware section headers (year for Date sort, count
          // bucket for Duration, etc.) make the active sort obvious
          // from the rendered list — no separate icon-state indicator
          // needed.
          IconButton(
            onClick = { showSortSheet = true },
            modifier = Modifier.semantics { testTag = "topbar_sort" },
          ) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.library_cd_sort)) }
          // D.28.2 — view-mode toggle. Sits between sort and filter so
          // the row reads search → sort → view → filter → settings. The
          // icon shows the *target* mode (tap to switch), not the
          // current one — same pattern as a play/pause button.
          IconButton(
            onClick = {
              val next = activeViewMode.toggle()
              scope.launch {
                val custom = activeCustomTab
                if (custom != null) settingsRepository.setCustomTabViewMode(custom.id, next)
                else settingsRepository.setViewModeFor(activeTab, next)
              }
            },
            modifier = Modifier.semantics { testTag = "topbar_view_mode" },
          ) {
            // Icon shows the *next* mode (tap-to-switch).
            // List → tap shows Tile (adaptive grid).
            // Tile → tap shows TwoColumn.
            // TwoColumn → tap shows List.
            when (activeViewMode) {
              ViewMode.List -> Icon(
                Icons.Filled.GridView,
                contentDescription = stringResource(R.string.library_cd_view_mode_to_tile),
              )
              ViewMode.Tile -> Icon(
                Icons.Filled.ViewModule,
                contentDescription = stringResource(R.string.library_cd_view_mode_to_tile),
              )
              ViewMode.TwoColumn -> Icon(
                Icons.AutoMirrored.Filled.ViewList,
                contentDescription = stringResource(R.string.library_cd_view_mode_to_list),
              )
            }
          }
          // D.27.5 — Filter icon. The badge ("filter active" dot) is
          // applied via `BadgedBox` when the filter has any non-null
          // field. Tapping opens the filter sheet.
          val filterActive = !filter.isEmpty()
          IconButton(
            onClick = { showFilterSheet = true },
            modifier = Modifier.semantics { testTag = "topbar_filter" },
          ) {
            if (filterActive) {
              androidx.compose.material3.BadgedBox(
                badge = {
                  androidx.compose.material3.Badge(
                    modifier = Modifier.semantics { testTag = "topbar_filter_badge" },
                  )
                },
              ) {
                Icon(Icons.Filled.FilterList, contentDescription = stringResource(R.string.library_cd_filter_active))
              }
            } else {
              Icon(Icons.Filled.FilterList, contentDescription = stringResource(R.string.library_cd_filter))
            }
          }
          // D.16.3 — direct Settings entry. The kebab dropdown is gone;
          // Refresh / Rescan now live exclusively under Settings → Library
          // (where the catalog already advertises them) so the top-bar
          // is no longer surface-noise for rarely-used actions.
          IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.semantics { testTag = "topbar_settings" },
          ) {
            Icon(
              Icons.Outlined.Settings,
              contentDescription = stringResource(R.string.library_cd_settings),
            )
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    // Vertical-rail layout: rail on the left, content on the right.
    // The rail extends the full content height (Scaffold-padded so we
    // sit under the top app bar and over the mini-player). Consume
    // ONLY top + horizontal insets — the bottom system inset is
    // already accounted for by `libraryBottomPad` at the app root
    // (TonearmboyApp), which sizes the library to end exactly at the
    // mini-player's top edge. Reapplying the nav-bar inset here would
    // stack on top of that and reopen the gap above the mini-player.
    val layoutDir = androidx.compose.ui.platform.LocalLayoutDirection.current
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(
          top = innerPadding.calculateTopPadding(),
          start = innerPadding.calculateStartPadding(layoutDir),
          end = innerPadding.calculateEndPadding(layoutDir),
        ),
    ) {
    // Library scan progress — appears at the top while a scan runs,
    // disappears when done. Bound to LibraryRepository.scanProgress.
    ScanProgressBar(scanner = scanner)
    Row(modifier = Modifier.fillMaxSize()) {
      LibraryRail(
        tabs = visibleTabs,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        // D.16.2 — rail gear is now a *shortcut* to the Library-tabs
        // configuration screen, not the Settings root. The user already
        // identified the gear-on-the-rail as "tab settings", so this
        // matches accepted intent.
        onOpenSettings = onOpenLibraryTabsConfig,
        customTabs = customTabsList,
      )
      Box(modifier = Modifier.fillMaxSize()) {
        if (activeCustomTab != null) {
          CustomTabContent(
            customTab = activeCustomTab,
            tracks = tracks,
            albums = albums,
            artists = artists,
            genres = genres,
            settingsRepository = settingsRepository,
            sort = activeSort,
            intelligentSorting = intelligentSorting,
            forceSquareCovers = forceSquareCovers,
            albumCoversMode = albumCoversMode,
            viewMode = activeViewMode,
            trackInteractions = TrackInteractions(
              onTrackClick = onTrackClick,
              onAddToQueue = onAddToQueue,
              onAddToPlaylist = onAddToPlaylist,
              onAddTracksToPlaylist = onAddTracksToPlaylist,
              onDeleteTracks = onDeleteTracks,
              onComingSoon = onComingSoon,
            ),
            nav = NavInteractions(
              onOpenAlbum = onOpenAlbum,
              onOpenArtist = onOpenArtist,
              onOpenGenre = onOpenGenre,
            ),
          )
        } else when (activeTab) {
          LibraryTab.Songs -> {
            // R1 — track-row "Search MusicBrainz" routes through the
            // album-level fetcher: a track without an MBID hint is too
            // noisy for a recording lookup, but its album is exactly the
            // album-level lookup AlbumDetail already uses, and a track
            // with no per-track override falls back to its album cover.
            val searchScope = rememberCoroutineScope()
            val ctx = LocalContext.current
            val onSearchTrackCover: (Track) -> Unit = { t ->
              val name = t.album
              if (name != null) {
                searchScope.launch {
                  val settings = com.eight87.tonearmboy.AppGraph
                    .get(ctx.applicationContext).settingsRepository
                  val service = settings.coverArtService.flow.first()
                  val mbScore = settings.coverArtMatchScore.flow.first()
                  com.eight87.tonearmboy.data.albumart.AlbumArtFetcher(albums)
                    .fetch(
                      context = ctx,
                      albumName = name,
                      albumArtist = t.albumArtist ?: t.artist,
                      service = service,
                      musicBrainzMinScore = mbScore,
                      overwriteUserChoice = true,
                    )
                }
              }
            }
            TracksListScreen(
              repository = tracks,
              sort = activeSort,
              intelligentSorting = intelligentSorting,
              filter = filter,
              viewMode = activeViewMode,
              albumCoversMode = albumCoversMode,
              onTrackClick = onTrackClick,
              onAddToQueue = onAddToQueue,
              onAddToPlaylist = onAddToPlaylist,
              onAddTracksToPlaylist = onAddTracksToPlaylist,
              onGoToAlbum = { t -> onOpenAlbum(t.album ?: return@TracksListScreen, t.albumArtist ?: t.artist) },
              onGoToArtist = { t -> onOpenArtist((t.albumArtist?.takeIf { it.isNotBlank() } ?: t.artist) ?: return@TracksListScreen) },
              onComingSoon = onComingSoon,
              onDeleteTracks = onDeleteTracks,
              onSearchTrackCover = onSearchTrackCover,
            )
          }
          LibraryTab.Albums -> AlbumsTabScreen(
            repository = albums,
            sort = activeSort,
            intelligentSorting = intelligentSorting,
            forceSquare = forceSquareCovers,
            albumCoversMode = albumCoversMode,
            viewMode = activeViewMode,
            onAlbumClick = { a -> onOpenAlbum(a.name, a.artist) },
          )
          LibraryTab.Artists -> ArtistsTabScreen(
            repository = artists,
            settingsRepository = settingsRepository,
            sort = activeSort,
            intelligentSorting = intelligentSorting,
            viewMode = activeViewMode,
            onArtistClick = { a -> onOpenArtist(a.name) },
          )
          LibraryTab.Genres -> GenresTabScreen(
            repository = genres,
            sort = activeSort,
            viewMode = activeViewMode,
            onGenreClick = { g -> onOpenGenre(g.name) },
          )
          LibraryTab.Playlists -> PlaylistsTabScreen(
            repository = playlists,
            viewMode = activeViewMode,
            onPlaylistClick = onPlaylistClick,
            onRenamePlaylist = onRenamePlaylist,
            onDeletePlaylist = onDeletePlaylist,
            onSetPlaylistCover = onSetPlaylistCover,
          )
        }
      }
    }
    }
  }

  if (showSortSheet) {
    SortSheet(
      current = activeSort,
      onDismiss = { showSortSheet = false },
      onConfirm = { newSort ->
        scope.launch { settingsRepository.setTabSort(activeTab, newSort) }
        showSortSheet = false
      },
    )
  }

  if (showFilterSheet) {
    val tracksForRange by tracks.observeTracks().collectAsState(initial = emptyList())
    LibraryFilterSheet(
      current = filter,
      tracks = tracksForRange,
      onDismiss = { showFilterSheet = false },
      onApply = {
        onFilterChange(it)
        showFilterSheet = false
      },
      onReset = {
        onFilterChange(com.eight87.tonearmboy.data.FilterCriteria())
        showFilterSheet = false
      },
    )
  }
}

// R.D.1 — sort + section-key helpers + tabLabel moved to `LibrarySorting.kt`.

// R.D.1 — section-key helpers + initialKey moved to `LibrarySorting.kt`.
// Shared row helpers (TwoLineRow / SectionHeader / MultiSelectBar /
// EmptyState) moved to `tabs/`. The legacy AlphabetScroller (replaced
// by the FastScrollbar bubble in R.A.Q) is dropped as dead code,
// along with the unused `computeFlatIndex`.
