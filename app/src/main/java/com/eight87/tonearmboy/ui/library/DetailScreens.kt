package com.eight87.tonearmboy.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.data.AlbumCoverChoice
import com.eight87.tonearmboy.data.AlbumSource
import com.eight87.tonearmboy.data.TrackSource
import com.eight87.tonearmboy.data.albumKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.eight87.tonearmboy.data.model.Album
import com.eight87.tonearmboy.data.model.Track
import com.eight87.tonearmboy.ui.nav.LocalSectionTitle
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode
import com.eight87.tonearmboy.ui.settings.catalog.SettingsDimens

/**
 * D.15 — detail screens for Albums / Artists / Genres.
 *
 * Each screen reads from the existing LibraryRepository Flows
 * (observeTracks / observeAlbums) and filters in-memory. For the
 * library sizes tonearmboy targets (single-digit thousands of tracks),
 * filtering at the Compose layer is plenty cheap; if the user pushes
 * past that we'd add per-screen DAO queries.
 *
 * The track rows reuse the same overflow-menu callbacks the library
 * tabs use so Add-to-queue / Add-to-playlist / Go-to-album/artist all
 * work identically here.
 */

// --- Album --------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
  // R.A.4 — read both rollups; pre-existing in-Compose join over them.
  trackSource: TrackSource,
  albumSource: AlbumSource,
  albumName: String,
  albumArtist: String?,
  albumCoversMode: AlbumCoversMode,
  onTrackClick: (List<Track>, Int) -> Unit,
  onTrackAction: (Track, TrackContextAction) -> Unit,
  onBack: () -> Unit,
) {
  // R.F.12 — narrow Flow; repository pre-filters instead of
  // observeTracks() + filter-in-Compose.
  val albumTracks by trackSource.observeTracksForAlbum(albumName, albumArtist)
    .collectAsState(initial = emptyList())
  val allAlbumsList by albumSource.observeAlbums().collectAsState(initial = emptyList())

  val tracks = remember(albumTracks) {
    albumTracks.sortedWith(
      compareBy({ it.year ?: Int.MIN_VALUE }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title }),
    )
  }
  val album: Album? = remember(allAlbumsList, albumName, albumArtist) {
    allAlbumsList.firstOrNull { it.name == albumName && it.artist == albumArtist }
  }
  val totalDurationMs = remember(tracks) { tracks.sumOf { it.durationMs } }

  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(albumName) { sectionTitle.value = albumName }

  // Phase A — per-album cover override. The TopAppBar overflow menu
  // gains Replace cover / Remove cover / Reset cover; the cover row
  // pulls from `AlbumCoverChoice` and falls back to MediaStore when
  // there's no user choice.
  val coverChoiceKey = remember(albumName, albumArtist) { albumKey(albumName, albumArtist) }
  val coverChoice by albumSource.albumCoverChoice(coverChoiceKey)
    .collectAsState(initial = AlbumCoverChoice.NoChoice)
  val context = LocalContext.current
  val coverScope = rememberCoroutineScope()
  var coverMenuOpen by remember { mutableStateOf(false) }
  // Cover-art Phase E.3 — snackbar names which provider hit so the
  // user can tell which slot in their priority list is pulling weight.
  val snackbarHostState = remember { SnackbarHostState() }
  val loadedFromTemplate = stringResource(R.string.library_album_cover_loaded_from)
  val notFoundMsg = stringResource(R.string.library_album_cover_not_found)
  val chainDisabledMsg = stringResource(R.string.library_album_cover_chain_disabled)
  val pickCoverLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      // Take a persistable read grant so the URI keeps resolving
      // after process death / app relaunch.
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      }
      coverScope.launch {
        albumSource.setAlbumCoverUri(coverChoiceKey, uri.toString())
      }
    }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      TopAppBar(
        title = { Text(albumName) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.library_cd_back))
          }
        },
        actions = {
          IconButton(
            onClick = { coverMenuOpen = true },
            modifier = Modifier.semantics { testTag = "album_detail_overflow" },
          ) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_album_cover_menu_cd))
          }
          DropdownMenu(
            expanded = coverMenuOpen,
            onDismissRequest = { coverMenuOpen = false },
          ) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.library_album_cover_replace)) },
              onClick = {
                coverMenuOpen = false
                pickCoverLauncher.launch(arrayOf("image/*"))
              },
            )
            // album-art Phase D — on-tap MusicBrainz lookup. Always
            // available (overwrites only if user hasn't pinned /
            // intentionally-cleared); the fetcher dispatches off the
            // UI thread.
            DropdownMenuItem(
              text = { Text(stringResource(R.string.library_album_cover_search)) },
              onClick = {
                coverMenuOpen = false
                coverScope.launch {
                  val settings = com.eight87.tonearmboy.AppGraph
                    .get(context.applicationContext).settingsRepository
                  if (settings.coverArtDisabled.flow.first()) {
                    snackbarHostState.showSnackbar(chainDisabledMsg)
                    return@launch
                  }
                  val configs = settings.coverArtProviders.flow.first()
                  val mbScore = settings.coverArtMatchScore.flow.first()
                  val pipedRaw = settings.pipedInstances.flow.first()
                  val pipedInstances = pipedRaw.split(',').map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { com.eight87.tonearmboy.data.albumart.PipedClient.DEFAULT_PIPED_INSTANCES }
                  val deps = com.eight87.tonearmboy.data.albumart.ProviderRegistry.Deps(
                    piped = com.eight87.tonearmboy.data.albumart.PipedClient(instances = pipedInstances),
                    // Round 4 — same COMMENT-tag fallback as the bulk worker.
                    tagReader = com.eight87.tonearmboy.data.albumart.AndroidTrackTagReader(context),
                  )
                  val chain = com.eight87.tonearmboy.data.albumart.ProviderRegistry
                    .buildChain(configs, deps)
                  val samplePath = albumSource.firstTrackPathForAlbum(coverChoiceKey)
                  val fetcher = com.eight87.tonearmboy.data.albumart.AlbumArtFetcher(albumSource)
                  val result = fetcher.fetch(
                    context = context,
                    albumName = albumName,
                    albumArtist = albumArtist,
                    sampleTrackPath = samplePath,
                    chain = chain,
                    musicBrainzMinScore = mbScore,
                    overwriteUserChoice = true,
                  )
                  val msg = when (result) {
                    is com.eight87.tonearmboy.data.albumart.AlbumArtFetcher.FetchResult.Saved ->
                      loadedFromTemplate.format(
                        result.providerKind?.name ?: "Unknown",
                      )
                    is com.eight87.tonearmboy.data.albumart.AlbumArtFetcher.FetchResult.NotFound -> notFoundMsg
                    com.eight87.tonearmboy.data.albumart.AlbumArtFetcher.FetchResult.ServiceDisabled -> chainDisabledMsg
                    is com.eight87.tonearmboy.data.albumart.AlbumArtFetcher.FetchResult.Failed -> notFoundMsg
                    com.eight87.tonearmboy.data.albumart.AlbumArtFetcher.FetchResult.AlreadyPinned,
                    com.eight87.tonearmboy.data.albumart.AlbumArtFetcher.FetchResult.IntentionallyEmpty -> null
                  }
                  if (msg != null) snackbarHostState.showSnackbar(msg)
                }
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.library_album_cover_clear)) },
              onClick = {
                coverMenuOpen = false
                coverScope.launch {
                  albumSource.clearAlbumCoverIntentional(coverChoiceKey)
                }
              },
            )
            if (coverChoice !is AlbumCoverChoice.NoChoice) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.library_album_cover_reset)) },
                onClick = {
                  coverMenuOpen = false
                  coverScope.launch {
                    albumSource.resetAlbumCover(coverChoiceKey)
                  }
                },
              )
            }
          }
        },
      )
    },
  ) { innerPadding ->
    // D.16.1 — detail screen body sits inside the same card chrome as
    // settings: cover and metadata in one card at the top, the tracks
    // list in another card below.
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .semantics { testTag = "album_detail" },
      verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardSpacing),
      contentPadding = PaddingValues(vertical = SettingsDimens.CardSpacing),
    ) {
      item {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .libraryDetailCard()
            .padding(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          // Phase A — translate the tri-state user choice into the
          // (albumId, coverUriOverride) pair CoverArt understands:
          //   - Pinned(uri)         → override = uri, albumId untouched
          //   - IntentionallyEmpty  → null both, CoverArt renders the
          //                           music-note placeholder.
          //   - NoChoice            → override = null, albumId = the
          //                           usual MediaStore id (default chain).
          val isIntentionallyEmpty = coverChoice is AlbumCoverChoice.IntentionallyEmpty
          val resolvedAlbumId = if (isIntentionallyEmpty) {
            null
          } else {
            album?.mediaStoreAlbumId ?: tracks.firstOrNull()?.mediaStoreAlbumId
          }
          val resolvedOverride = (coverChoice as? AlbumCoverChoice.Pinned)?.uri
          CoverArt(
            albumId = resolvedAlbumId,
            size = 192.dp,
            mode = albumCoversMode,
            contentDescription = albumName,
            coverUriOverride = resolvedOverride,
            albumName = albumName,
            albumArtist = albumArtist,
            modifier = Modifier
              .fillMaxWidth(0.7f)
              .aspectRatio(1f)
              .clip(RoundedCornerShape(12.dp))
              .semantics { testTag = "album_detail_cover" },
          )
          Spacer(Modifier.height(12.dp))
          Text(albumName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
          Text(
            text = albumArtist ?: stringResource(R.string.library_unknown_artist),
            style = MaterialTheme.typography.bodyMedium,
          )
          Text(
            text = listOfNotNull(
              album?.year?.toString(),
              pluralStringResource(R.plurals.library_album_detail_meta_tracks, tracks.size, tracks.size),
              formatDuration(totalDurationMs),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      // Tracks card body. Each row composes inside a single card; we
      // wrap the section in a stickyHeader-free Column so the rounded
      // corners don't repeat on every row.
      item {
        Column(modifier = Modifier.libraryDetailCard()) {
          tracks.forEachIndexed { index, track ->
            DetailTrackRow(
              track = track,
              onClick = { onTrackClick(tracks, index) },
              onAction = { action -> onTrackAction(track, action) },
            )
          }
        }
      }
    }
  }
}

// --- Artist --------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArtistDetailScreen(
  trackSource: TrackSource,
  albumSource: AlbumSource,
  // R3 — needed for the artist-cover override write surface.
  artistSource: com.eight87.tonearmboy.data.ArtistSource,
  artistName: String,
  albumCoversMode: AlbumCoversMode,
  onTrackClick: (List<Track>, Int) -> Unit,
  onTrackAction: (Track, TrackContextAction) -> Unit,
  onAlbumClick: (Album) -> Unit,
  onBack: () -> Unit,
) {
  // R.F.12 — narrow Flow; repository pre-filters by artist.
  val artistTracks by trackSource.observeTracksForArtist(artistName)
    .collectAsState(initial = emptyList())
  val allAlbums by albumSource.observeAlbums().collectAsState(initial = emptyList())

  val tracks = remember(artistTracks) {
    artistTracks.sortedWith(
      compareBy({ it.album ?: "" }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title }),
    )
  }
  val albums = remember(allAlbums, artistName) {
    allAlbums.filter { it.artist == artistName }
      .sortedWith(compareBy({ it.year ?: Int.MIN_VALUE }, { it.name }))
  }

  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(artistName) { sectionTitle.value = artistName }

  // R3 — per-artist cover override drives the topbar overflow menu.
  val artistCover by artistSource.artistCoverChoice(artistName)
    .collectAsState(initial = AlbumCoverChoice.NoChoice)
  val artistCoverHandlers = com.eight87.tonearmboy.ui.library.rememberArtistCoverActions(
    artistSource = artistSource,
    artistName = artistName,
    onSearchOnline = {},
  ).copy(showSearchOnline = false)
  var artistCoverMenuOpen by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(artistName) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.library_cd_back))
          }
        },
        actions = {
          IconButton(
            onClick = { artistCoverMenuOpen = true },
            modifier = Modifier.semantics { testTag = "artist_detail_overflow" },
          ) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_artist_overflow_cd))
          }
          DropdownMenu(
            expanded = artistCoverMenuOpen,
            onDismissRequest = { artistCoverMenuOpen = false },
          ) {
            com.eight87.tonearmboy.ui.library.CoverActionsMenuItems(
              choice = artistCover,
              handlers = artistCoverHandlers,
              onDismiss = { artistCoverMenuOpen = false },
            )
          }
        },
      )
    },
  ) { innerPadding ->
    // D.16.1 — same chrome split as AlbumDetail. Header card with
    // metadata; horizontal albums section in its own card; tracks list
    // in a third card so each section reads as a coherent group.
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .semantics { testTag = "artist_detail" },
      verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardSpacing),
      contentPadding = PaddingValues(vertical = SettingsDimens.CardSpacing),
    ) {
      item {
        Column(modifier = Modifier.libraryDetailCard().padding(16.dp)) {
          Text(artistName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
          Text(
            text = pluralStringResource(R.plurals.library_artist_detail_albums_count, albums.size, albums.size) +
              " · " +
              pluralStringResource(R.plurals.library_artist_detail_tracks_count, tracks.size, tracks.size),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      if (albums.isNotEmpty()) {
        item {
          Column(modifier = Modifier.libraryDetailCard().padding(vertical = 8.dp)) {
            Text(
              text = stringResource(R.string.library_detail_artist_albums_section),
              style = MaterialTheme.typography.titleSmall,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyHorizontalGrid(
              rows = GridCells.Fixed(1),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(horizontal = 16.dp)
                .semantics { testTag = "artist_detail_albums" },
            ) {
              items(albums, key = { it.id }) { album ->
                Column(
                  modifier = Modifier
                    .clickable { onAlbumClick(album) }
                    .padding(4.dp),
                ) {
                  CoverArt(
                    albumId = album.mediaStoreAlbumId,
                    size = 48.dp,
                    mode = albumCoversMode,
                    contentDescription = album.name,
                    modifier = Modifier
                      .size(120.dp)
                      .clip(RoundedCornerShape(8.dp)),
                  )
                  Text(album.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
              }
            }
          }
        }
      }
      item {
        Column(modifier = Modifier.libraryDetailCard()) {
          Text(
            text = stringResource(R.string.library_detail_artist_tracks_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          )
          tracks.forEachIndexed { index, track ->
            DetailTrackRow(
              track = track,
              onClick = { onTrackClick(tracks, index) },
              onAction = { action -> onTrackAction(track, action) },
            )
          }
        }
      }
    }
  }
}

// --- Genre --------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreDetailScreen(
  trackSource: TrackSource,
  genreName: String,
  onTrackClick: (List<Track>, Int) -> Unit,
  onTrackAction: (Track, TrackContextAction) -> Unit,
  onBack: () -> Unit,
) {
  // R.F.12 — narrow Flow; repository pre-filters by genre.
  val genreTracks by trackSource.observeTracksForGenre(genreName)
    .collectAsState(initial = emptyList())
  val tracks = remember(genreTracks) { genreTracks.sortedBy { it.title } }

  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(genreName) { sectionTitle.value = genreName }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(genreName) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.library_cd_back))
          }
        },
      )
    },
  ) { innerPadding ->
    // D.16.1 — header + tracks each in their own card.
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .semantics { testTag = "genre_detail" },
      verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardSpacing),
      contentPadding = PaddingValues(vertical = SettingsDimens.CardSpacing),
    ) {
      item {
        Column(modifier = Modifier.fillMaxWidth().libraryDetailCard().padding(16.dp)) {
          Text(genreName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
          Text(
            text = pluralStringResource(R.plurals.library_genre_detail_tracks_count, tracks.size, tracks.size),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      item {
        Column(modifier = Modifier.libraryDetailCard()) {
          tracks.forEachIndexed { index, track ->
            DetailTrackRow(
              track = track,
              onClick = { onTrackClick(tracks, index) },
              onAction = { action -> onTrackAction(track, action) },
            )
          }
        }
      }
    }
  }
}

// --- Track row + actions used across detail surfaces -------------------
// R.F.1 — TrackContextAction declared in `TrackContextAction.kt`,
// shared with the library Songs tab. Overflow menu shared via TrackContextMenu.

@Composable
private fun DetailTrackRow(
  track: Track,
  onClick: () -> Unit,
  onAction: (TrackContextAction) -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 10.dp)
      .semantics { testTag = "detail_track_row" },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
      Text(
        text = listOfNotNull(track.artist?.takeIf { it.isNotBlank() }, track.album?.takeIf { it.isNotBlank() })
          .joinToString(" · ").ifEmpty { stringResource(R.string.library_unknown) },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
      )
    }
    Box {
      IconButton(
        onClick = { menuOpen = true },
        modifier = Modifier.semantics { testTag = "detail_track_overflow" },
      ) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_cd_more_options)) }
      TrackContextMenu(
        expanded = menuOpen,
        onDismiss = { menuOpen = false },
        onAction = onAction,
        deleteTestTag = "detail_track_context_delete",
      )
    }
  }
  HorizontalDivider()
}

/**
 * D.16.1 — detail-screen variant of the library card chrome. Same
 * `surfaceContainer` background + rounded corners + page padding as
 * `Modifier.libraryListCard()`, factored separately so the detail
 * screens (which use plain `Column` + `LazyColumn item {}` blocks
 * rather than top-level lazy lists) don't pay for `clip` twice.
 */
@Composable
internal fun Modifier.libraryDetailCard(): Modifier {
  val bg = MaterialTheme.colorScheme.surfaceContainer
  return this
    .padding(horizontal = SettingsDimens.PagePadding)
    .clip(RoundedCornerShape(SettingsDimens.CardCornerRadius))
    .background(bg)
    .semantics { testTag = "library_detail_card" }
}

internal fun formatDuration(durationMs: Long): String {
  val totalSeconds = durationMs / 1000
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
  else "%d:%02d".format(minutes, seconds)
}
