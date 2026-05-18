package com.eight87.tonearmboy.ui.nav.routes

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.eight87.tonearmboy.data.FilterCriteria
import com.eight87.tonearmboy.data.db.CustomTabEntity
import com.eight87.tonearmboy.data.model.Track
import com.eight87.tonearmboy.playback.PlaybackUiController
import com.eight87.tonearmboy.ui.library.AlbumDetailScreen
import com.eight87.tonearmboy.ui.library.TrackContextAction
import com.eight87.tonearmboy.ui.library.ArtistDetailScreen
import com.eight87.tonearmboy.ui.library.CustomTabEditorScreen
import com.eight87.tonearmboy.ui.library.FilterUniverse
import com.eight87.tonearmboy.ui.library.GenreDetailScreen
import com.eight87.tonearmboy.ui.library.LibraryScreen
import com.eight87.tonearmboy.ui.library.PlaylistDetailScreen
import com.eight87.tonearmboy.ui.library.TrackPickerScreen
import com.eight87.tonearmboy.ui.nav.AlbumDetail
import com.eight87.tonearmboy.ui.nav.ArtistDetail
import com.eight87.tonearmboy.ui.nav.CustomTabEditor
import com.eight87.tonearmboy.ui.nav.GenreDetail
import com.eight87.tonearmboy.ui.nav.LibraryRoot
import com.eight87.tonearmboy.ui.nav.LocalSectionTitle
import com.eight87.tonearmboy.ui.nav.NowPlaying
import com.eight87.tonearmboy.ui.nav.PlaylistDetail
import com.eight87.tonearmboy.ui.nav.PlaylistTrackPicker
import com.eight87.tonearmboy.ui.nav.RouteScope
import com.eight87.tonearmboy.ui.nav.SettingsPersonalize
import com.eight87.tonearmboy.ui.nav.SettingsRootDest
import com.eight87.tonearmboy.ui.nav.Search
import com.eight87.tonearmboy.ui.nav.TonearmboyBackStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * R.E.2 — per-destination [LibraryRoot] / detail / picker / editor
 * `Register` extensions. Each one is the content that previously lived
 * inline in `TonearmboyApp.kt`'s `entryProvider` block, now closed
 * against modification — adding a new library destination is a new
 * `Register` extension on the new sealed-type variant.
 */

@OptIn(UnstableApi::class)
@Composable
fun LibraryRoot.Register(scope: RouteScope) {
  val context = androidx.compose.ui.platform.LocalContext.current
  with(scope) {
    LibraryScreen(
      tracks = graph.tracks,
      albums = graph.albums,
      artists = graph.artists,
      genres = graph.genres,
      playlists = graph.playlists,
      customTabs = graph.customTabs,
      scanner = graph.scanner,
      settingsRepository = graph.settingsRepository,
      onTrackClick = { tracks, index ->
        // D.9a.4: queue depends on the user's "When playing from
        // the library" choice. Surrounding list is whatever the
        // tab gave us; we treat that as both `surroundingList`
        // and `allSongs` since the library Songs tab IS all songs.
        playback.playFromLibrary(
          surroundingList = tracks,
          tappedIndex = index,
          strategy = playFromLibrary,
        )
        onOpenNowPlayingSheet()
      },
      onPlaylistClick = { id -> backStack.push(PlaylistDetail(id)) },
      onOpenSearch = { backStack.push(Search) },
      // D.16.3 — top-right wheel goes straight to the Settings root.
      onOpenSettings = { backStack.push(SettingsRootDest) },
      // D.16.2 — the rail's bottom-left gear is the "tab
      // customization" shortcut: pushes the Personalize sub-page
      // (which holds Library tabs) directly. Back from Personalize
      // returns straight to the library — the user came from the
      // library, not from Settings, so seeding Settings underneath
      // would be a surprise on the way out (G+ user feedback).
      onOpenLibraryTabsConfig = {
        backStack.push(SettingsPersonalize)
      },
      onComingSoon = onComingSoon,
      snackbarHostState = snackbar,
      onOpenAlbum = { name, albumArtist -> backStack.push(AlbumDetail(name, albumArtist)) },
      onOpenArtist = { name -> backStack.push(ArtistDetail(name)) },
      onOpenGenre = { name -> backStack.push(GenreDetail(name)) },
      onAddToQueue = { track ->
        playback.addToQueue(track)
        applicationScope.launch {
          snackbar.showSnackbar(
            context.getString(com.eight87.tonearmboy.R.string.library_added_to_queue, track.title),
          )
        }
      },
      onAddToPlaylist = { track -> addToPlaylist.requestSingle(track) },
      onAddTracksToPlaylist = { ids -> addToPlaylist.requestBulk(ids) },
      onRenamePlaylist = { id, name ->
        applicationScope.launch {
          graph.playlists.renamePlaylist(id, name)
          snackbar.showSnackbar(
            context.getString(com.eight87.tonearmboy.R.string.library_playlist_renamed, name),
          )
        }
      },
      onDeletePlaylist = { id ->
        applicationScope.launch {
          graph.playlists.deletePlaylist(id)
          snackbar.showSnackbar(
            context.getString(com.eight87.tonearmboy.R.string.library_playlist_deleted),
          )
        }
      },
      onSetPlaylistCover = { id, uri ->
        applicationScope.launch {
          graph.playlists.setPlaylistCoverUri(id, uri)
          snackbar.showSnackbar(
            context.getString(
              if (uri == null) com.eight87.tonearmboy.R.string.library_playlist_cover_cleared
              else com.eight87.tonearmboy.R.string.library_playlist_cover_updated,
            ),
          )
        }
      },
      onOpenPlaylistDetail = { id -> backStack.push(PlaylistDetail(id)) },
      filter = libraryFilter,
      onFilterChange = onLibraryFilterChange,
      onDeleteTracks = onDeleteTracks,
    )
  }
}

@OptIn(UnstableApi::class)
@Composable
fun AlbumDetail.Register(scope: RouteScope) {
  val context = androidx.compose.ui.platform.LocalContext.current
  with(scope) {
    AlbumDetailScreen(
      trackSource = graph.tracks,
      albumSource = graph.albums,
      albumName = name,
      albumArtist = albumArtist,
      albumCoversMode = albumCoversMode,
      onTrackClick = { tracks, index ->
        playback.playFromDetail(
          surroundingList = tracks,
          tappedIndex = index,
          strategy = playFromItemDetails,
        )
        onOpenNowPlayingSheet()
      },
      onTrackAction = { track, action ->
        handleDetailTrackAction(
          track = track, action = action,
          playback = playback, backStack = backStack,
          snackbar = snackbar, applicationScope = applicationScope,
          context = context,
          onAddToPlaylist = { addToPlaylist.requestSingle(it) },
          onDeleteTracks = onDeleteTracks,
        )
      },
      onBack = { backStack.pop() },
    )
  }
}

@OptIn(UnstableApi::class)
@Composable
fun ArtistDetail.Register(scope: RouteScope) {
  val context = androidx.compose.ui.platform.LocalContext.current
  with(scope) {
    ArtistDetailScreen(
      trackSource = graph.tracks,
      albumSource = graph.albums,
      artistSource = graph.artists,
      artistName = name,
      albumCoversMode = albumCoversMode,
      onTrackClick = { tracks, index ->
        playback.playFromDetail(
          surroundingList = tracks,
          tappedIndex = index,
          strategy = playFromItemDetails,
        )
        onOpenNowPlayingSheet()
      },
      onTrackAction = { track, action ->
        handleDetailTrackAction(
          track = track, action = action,
          playback = playback, backStack = backStack,
          snackbar = snackbar, applicationScope = applicationScope,
          context = context,
          onAddToPlaylist = { addToPlaylist.requestSingle(it) },
          onDeleteTracks = onDeleteTracks,
        )
      },
      onAlbumClick = { album -> backStack.push(AlbumDetail(album.name, album.artist)) },
      onBack = { backStack.pop() },
    )
  }
}

@OptIn(UnstableApi::class)
@Composable
fun GenreDetail.Register(scope: RouteScope) {
  val context = androidx.compose.ui.platform.LocalContext.current
  with(scope) {
    GenreDetailScreen(
      trackSource = graph.tracks,
      genreName = name,
      albumCoversMode = albumCoversMode,
      onTrackClick = { tracks, index ->
        playback.playFromDetail(
          surroundingList = tracks,
          tappedIndex = index,
          strategy = playFromItemDetails,
        )
        onOpenNowPlayingSheet()
      },
      onTrackAction = { track, action ->
        handleDetailTrackAction(
          track = track, action = action,
          playback = playback, backStack = backStack,
          snackbar = snackbar, applicationScope = applicationScope,
          context = context,
          onAddToPlaylist = { addToPlaylist.requestSingle(it) },
          onDeleteTracks = onDeleteTracks,
        )
      },
      onBack = { backStack.pop() },
    )
  }
}

@OptIn(UnstableApi::class)
@Composable
fun PlaylistDetail.Register(scope: RouteScope) {
  with(scope) {
    PlaylistDetailScreen(
      repository = graph.playlists,
      playlistId = playlistId,
      onTrackClick = { tracks, index ->
        // D.9a.5: tapped from a detail surface (playlist).
        playback.playFromDetail(
          surroundingList = tracks,
          tappedIndex = index,
          strategy = playFromItemDetails,
        )
        onOpenNowPlayingSheet()
      },
      onBack = { backStack.pop() },
      onAddTracks = { id -> backStack.push(PlaylistTrackPicker(id)) },
    )
  }
}

@Composable
fun PlaylistTrackPicker.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "Add tracks" }
  val context = androidx.compose.ui.platform.LocalContext.current
  with(scope) {
    TrackPickerScreen(
      trackSource = graph.tracks,
      playlists = graph.playlists,
      playlistId = playlistId,
      onBack = { backStack.pop() },
      onConfirm = { toAdd, toRemove ->
        applicationScope.launch {
          toAdd.forEach { tid ->
            graph.playlists.addTrackToPlaylist(playlistId, tid)
          }
          if (toRemove.isNotEmpty()) {
            // Remove by re-writing the playlist's join rows
            // without the unchecked ids; the repository helper
            // doesn't take an id-set directly so we use the same
            // pattern as the M3U importer.
            val current = graph.playlists
              .observePlaylistTracks(playlistId).first()
              .map { it.id }
            val remaining = current.filter { it !in toRemove }
            graph.playlists.reorderPlaylist(playlistId, remaining)
          }
          snackbar.showSnackbar(
            context.getString(
              com.eight87.tonearmboy.R.string.library_playlist_updated,
              toAdd.size,
              toRemove.size,
            ),
          )
        }
        backStack.pop()
      },
    )
  }
}

@Composable
fun CustomTabEditor.Register(scope: RouteScope) {
  // D.30.3 — full-screen editor for a custom library tab. The
  // entity (when editing) is resolved from the live customTabs
  // Flow on entry; FilterUniverse is built from the same Flows
  // the dialog used pre-D.30.3.
  with(scope) {
    val customTabs by graph.customTabs.customTabs().collectAsStateWithLifecycle(initialValue = emptyList())
    val genres by graph.genres.observeGenres().collectAsStateWithLifecycle(initialValue = emptyList())
    val artists by graph.artists.observeArtists().collectAsStateWithLifecycle(initialValue = emptyList())
    val albums by graph.albums.observeAlbums().collectAsStateWithLifecycle(initialValue = emptyList())
    val tracks by graph.tracks.observeTracks().collectAsStateWithLifecycle(initialValue = emptyList())
    val existing = tabId?.let { id -> customTabs.firstOrNull { it.id == id } }
    val universe = remember(genres, artists, albums, tracks) {
      FilterUniverse(
        genres = genres.map { it.name }.distinct().sorted(),
        artists = artists.map { it.name }.distinct().sorted(),
        albums = albums.map { it.name }.distinct().sorted(),
        minYear = tracks.mapNotNull { it.year }.minOrNull(),
        maxYear = tracks.mapNotNull { it.year }.maxOrNull(),
      )
    }
    val sectionTitle = LocalSectionTitle.current
    LaunchedEffect(existing) {
      sectionTitle.value = if (existing == null) "New custom tab" else "Edit custom tab"
    }
    val coroutineScope = rememberCoroutineScope()
    CustomTabEditorScreen(
      existing = existing,
      universe = universe,
      onBack = { backStack.pop() },
      onSave = { name, ct, criteria ->
        coroutineScope.launch {
          val entity = (existing ?: CustomTabEntity(
            name = name,
            position = 0,
            contentType = ct,
            criteriaJson = "",
          )).copy(
            name = name,
            contentType = ct,
            criteriaJson = FilterCriteria.toJson(criteria),
          )
          graph.customTabs.upsertCustomTab(entity)
          backStack.pop()
        }
      },
    )
  }
}

/**
 * Shared between [AlbumDetail], [ArtistDetail], [GenreDetail] track-row
 * actions. Lifted from `TonearmboyApp.kt` as part of R.E.2.
 */
@OptIn(UnstableApi::class)
internal fun handleDetailTrackAction(
  track: Track,
  action: TrackContextAction,
  playback: PlaybackUiController,
  backStack: TonearmboyBackStack,
  snackbar: SnackbarHostState,
  applicationScope: CoroutineScope,
  context: android.content.Context,
  onAddToPlaylist: (Track) -> Unit,
  onDeleteTracks: (List<Track>) -> Unit,
) {
  when (action) {
    TrackContextAction.Play -> playback.playTrack(track)
    TrackContextAction.AddToQueue -> {
      playback.addToQueue(track)
      applicationScope.launch {
        snackbar.showSnackbar(
          context.getString(com.eight87.tonearmboy.R.string.library_added_to_queue, track.title),
        )
      }
    }
    TrackContextAction.AddToPlaylist -> onAddToPlaylist(track)
    TrackContextAction.GoToAlbum -> {
      val name = track.album ?: return
      backStack.push(AlbumDetail(name, track.albumArtist ?: track.artist))
    }
    TrackContextAction.GoToArtist -> {
      val name = track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artist ?: return
      backStack.push(ArtistDetail(name))
    }
    // Phase F.2 — single-track delete from album/artist/genre detail.
    TrackContextAction.Delete -> onDeleteTracks(listOf(track))
  }
}
