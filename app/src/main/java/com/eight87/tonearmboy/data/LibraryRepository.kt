package com.eight87.tonearmboy.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.eight87.tonearmboy.data.Mapping.toDomain
import com.eight87.tonearmboy.data.Mapping.toEntity
import com.eight87.tonearmboy.data.db.CustomTabEntity
import com.eight87.tonearmboy.data.db.LibraryDatabase
import com.eight87.tonearmboy.data.db.PlaylistEntity
import com.eight87.tonearmboy.data.db.SearchExpressions
import com.eight87.tonearmboy.data.db.TrackEntity
import com.eight87.tonearmboy.data.mediastore.MediaStoreScanner
import com.eight87.tonearmboy.data.mediastore.SafScopeMapping
import com.eight87.tonearmboy.data.model.Album
import com.eight87.tonearmboy.data.model.Artist
import com.eight87.tonearmboy.data.model.Genre
import com.eight87.tonearmboy.data.model.Playlist
import com.eight87.tonearmboy.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for the library. Owns the [MediaStoreScanner]
 * (the device's authoritative metadata) and the [LibraryDatabase] (the
 * cached queryable mirror), and exposes Flows that emit whenever the
 * underlying tables change.
 *
 * The repository performs the initial scan on construction-and-first-
 * subscription, then keeps the cache in sync via a
 * [ContentObserver] registered on the audio collection URI. Observer
 * callbacks are debounced to coalesce the bursts MediaStore emits when
 * a directory of files is indexed in one shot.
 *
 * No DI framework — `Context`, scanner, db, and scope are constructor
 * parameters, per the project convention.
 */
class LibraryRepository(
  // R.A.6 — no constructor self-defaults. `AppGraph` is the only
  // place that knows how to wire the four collaborators; tests
  // construct each one explicitly. Removing the defaults keeps the
  // dependency direction visible (`AppGraph → LibraryRepository`)
  // and stops a stray test from accidentally booting MainScope().
  private val context: Context,
  private val scanner: MediaStoreScanner,
  private val db: LibraryDatabase,
  private val externalScope: CoroutineScope,
  /**
   * R.A.5 — read on every scan to apply the user's multi-value
   * separator selection + music-source scope. Concrete impl
   * (`SettingsRepository`) lives in `ui.settings`; the data layer only
   * sees the [ScanConfigSource] interface, which keeps the dependency
   * direction `ui → data` (DIP).
   */
  private val scanConfig: ScanConfigSource,
) : TrackSource,
  AlbumSource,
  ArtistSource,
  GenreSource,
  PlaylistStore,
  CustomTabStore,
  LibraryScanner,
  MediaChangeSource {

  private val rescanRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
  private val initialScanDone = AtomicBoolean(false)
  private val scanMutex = Mutex()

  /**
   * Per-track progress emitted as the scanner walks MediaStore.
   * `null` means no scan is in flight; non-null means UI should render
   * a progress indicator. The StateFlow is conflated, so a UI consumer
   * sees the latest value and never blocks the scanner producer even
   * if it's recomposing slowly.
   */
  data class ScanProgress(
    val scanned: Int,
    val total: Int,
    val currentTitle: String?,
  ) {
    val fraction: Float get() = if (total <= 0) 0f else scanned.toFloat() / total.toFloat()
  }
  private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
  override val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

  /**
   * R.A.1 — `Flow<Unit>` of MediaStore content-observer ticks. The
   * repository's own debounced rescan loop subscribes via the
   * `rescanRequests` shared flow; this is the externally-visible
   * read-only projection of the same stream for [MediaChangeSource]
   * consumers.
   */
  override val mediaChanges: Flow<Unit> get() = rescanRequests

  // R.F.9 — shared observer plumbing (Data-F7). Debounce policy stays
  // here (in-process Flow.debounce); the watcher service does its own
  // WorkManager-based debouncing on top of the same primitive.
  private val mediaObserver = MediaChangeObserver(
    contentResolver = context.contentResolver,
    threadName = "tonearmboy-mediastore-observer",
    onChange = { rescanRequests.tryEmit(Unit) },
  )

  init {
    @OptIn(FlowPreview::class)
    externalScope.launch(Dispatchers.IO) {
      rescanRequests
        .debounce(RESCAN_DEBOUNCE_MS)
        .onEach { runScan(initial = false) }
        .collect()
    }
    mediaObserver.register(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
  }

  // -- Public surface --------------------------------------------------------

  override fun observeTracks(): Flow<List<Track>> {
    ensureInitialScan()
    return db.trackDao().observeAll().map { rows -> rows.map { it.toDomain() } }
  }

  override fun observeAlbums(): Flow<List<Album>> {
    ensureInitialScan()
    return db.albumDao().observeAlbumsWithCounts().map { rows ->
      rows.map {
        Album(
          id = it.id,
          name = it.name,
          artist = it.artist,
          trackCount = it.trackCount,
          year = it.year,
          mediaStoreAlbumId = it.mediaStoreAlbumId,
        )
      }
    }
  }

  override fun observeArtists(): Flow<List<Artist>> = observeArtists(flowOf(false))

  /**
   * D.9a.6 — when [hideCollaboratorsFlow] emits `true`, the artist list
   * is derived purely from each track's `albumArtist` (falling back to
   * `artist` when album-artist is missing). When it emits `false`, the
   * derivation also includes `artist` values that differ from the
   * primary `albumArtist` on the same track — i.e. featured collaborators.
   *
   * The toggle re-emits the new artist list without rescanning: the
   * Flow combines the cached track table with the settings Flow and
   * recomputes the rollup at query time. This is fine for small / mid
   * libraries; if the user has tens of thousands of tracks we'd switch
   * to two precomputed views in the DB.
   */
  override fun observeArtists(hideCollaboratorsFlow: Flow<Boolean>): Flow<List<Artist>> {
    ensureInitialScan()
    val tracks = db.trackDao().observeAll()
    return combine(tracks, hideCollaboratorsFlow) { rows, hide ->
      deriveArtistsFromTracks(rows, hide)
    }
  }

  override fun observeGenres(): Flow<List<Genre>> {
    ensureInitialScan()
    return db.genreDao().observeGenresWithCounts().map { rows ->
      rows.map { Genre(it.id, it.name, it.trackCount) }
    }
  }

  override fun observePlaylists(): Flow<List<Playlist>> =
    db.playlistDao().observePlaylistsWithCounts().map { rows ->
      rows.map { Playlist(it.id, it.name, it.trackCount, it.createdAtSeconds, it.coverUri) }
    }

  override fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
    db.playlistDao().observeTracks(playlistId).map { rows -> rows.map { it.toDomain() } }

  /**
   * D.27.6 — observe the first-track album-art id for a playlist tile.
   * Returns null when the playlist is empty or none of its tracks has
   * album art. Used by the tile cover-resolution chain.
   */
  override fun observePlaylistFirstAlbumArt(playlistId: Long): Flow<Long?> =
    db.playlistDao().observeFirstTrackAlbumId(playlistId)

  /**
   * Full-text search over (title, artist, album). Uses Room FTS4 when
   * possible, falls back to LIKE for inputs that are pure punctuation
   * or otherwise unsafe to feed to MATCH.
   */
  override fun search(query: String): Flow<List<Track>> {
    val match = SearchExpressions.ftsMatch(query)
    val flow = if (match != null) {
      db.trackDao().searchFts(match)
    } else {
      val trimmed = query.trim()
      if (trimmed.isEmpty()) {
        return kotlinx.coroutines.flow.flowOf(emptyList())
      }
      db.trackDao().searchLike(SearchExpressions.likePattern(trimmed))
    }
    return flow.map { rows -> rows.map { it.toDomain() } }
  }

  /** Force a rescan now (used by the smoke test + the Settings UI in Phase D). */
  override suspend fun rescanNow() {
    runScan(initial = false)
  }

  /**
   * Phase F.4 — invalidate the cache after the system has deleted the
   * underlying audio files. The caller (the Compose layer that owns
   * the consent-launcher result) hands us the list of MediaStore
   * content URIs the user agreed to delete; we strip the trailing id
   * from each one and run a single Room transaction that:
   *
   * 1. removes those rows from the `tracks` table,
   * 2. relies on the `playlist_tracks` cascade to clean up join rows,
   * 3. queues a debounced rescan so the rollup tables (albums /
   *    artists / genres) reconcile against the new track set without
   *    blocking the UI thread.
   *
   * Idempotent — invoking with an empty / already-dropped id list is a
   * no-op.
   */
  override suspend fun onTracksDeleted(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val ids = uris.mapNotNull { it.lastPathSegment?.toLongOrNull() }
    if (ids.isEmpty()) {
      // Still trigger a rescan — the URIs may not have been MediaStore
      // ids (caller passed file URIs, etc.) so the safest fallback is
      // to let MediaStore tell us what is actually gone.
      rescanRequests.tryEmit(Unit)
      return
    }
    db.trackDao().deleteByIds(ids)
    // Don't rebuild the rollups here synchronously — the
    // ContentObserver's MediaStore callback fires on file removal and
    // the debounced rescan picks them up. Emit one anyway in case the
    // observer hasn't run yet (e.g. the user denied the consent dialog
    // for one file in a batch but accepted others).
    rescanRequests.tryEmit(Unit)
  }

  /**
   * D.9b.1 — look up the album-level ReplayGain dB / peak for the
   * given (album name, album artist) pair. Returns
   * `(null, null)` when the album row has no album-level tags or no
   * row exists. The `albumArtist` lookup falls back to the per-track
   * artist when album-artist is missing — same precedence as
   * `Mapping.deriveAlbums` uses to compose the unique album key.
   */
  override suspend fun albumReplayGain(albumName: String?, albumArtist: String?): Pair<Float?, Float?> {
    if (albumName.isNullOrBlank()) return null to null
    val row = db.albumDao().byNameAndArtist(albumName, albumArtist)
    return row?.replayGainAlbumDb to row?.replayGainAlbumPeak
  }

  /** Track-by-id retrieval for the playback gain pipeline. */
  override suspend fun trackById(id: Long): Track? =
    db.trackDao().getById(id)?.toDomain()

  /**
   * D.9b.1 — count of tracks for a given album key. Used to compute
   * how much of the album the queue covers in Smart mode.
   */
  override suspend fun trackCountForAlbum(albumName: String?, albumArtist: String?): Int {
    if (albumName.isNullOrBlank()) return 0
    return db.trackDao().countForAlbum(albumName, albumArtist)
  }

  // -- Custom tabs (D.18) -----------------------------------------------------

  /** Live, position-ordered list of user-defined library tabs. */
  override fun customTabs(): Flow<List<CustomTabEntity>> = db.customTabDao().observeAll()

  /**
   * D.18.1 — tracks that match [criteria]. Filtering happens in
   * Kotlin against the live track Flow, on the IO dispatcher (matching
   * is cheap; the Flow is already cold-mapped on `Dispatchers.IO`
   * inside the DAO). For empty criteria this is the all-tracks Flow.
   */
  override fun tracksMatching(criteria: FilterCriteria): Flow<List<Track>> {
    val all = observeTracks()
    if (criteria.isEmpty()) return all
    return all.map { list -> list.filter { criteria.matchesTrack(it) } }
  }

  override fun albumsMatching(criteria: FilterCriteria): Flow<List<Album>> {
    if (criteria.isEmpty()) return observeAlbums()
    val tracks = observeTracks()
    val albums = observeAlbums()
    return combine(tracks, albums) { tracksList, albumsList ->
      // Group tracks by album-key (name + artist). Match each album row
      // against the predicate via its tracks.
      val grouped = tracksList.groupBy { (it.album ?: "") to ((it.albumArtist ?: it.artist) ?: "") }
      albumsList.filter { album ->
        val key = (album.name) to (album.artist ?: "")
        val tracksOfAlbum = grouped[key].orEmpty()
        criteria.matchesAlbum(album, tracksOfAlbum)
      }
    }
  }

  override fun albumCoverChoice(albumKey: String): Flow<AlbumCoverChoice> =
    db.albumCoverDao().row(albumKey).map { row ->
      when {
        row == null -> AlbumCoverChoice.NoChoice
        row.coverUri.isNullOrBlank() -> AlbumCoverChoice.IntentionallyEmpty
        else -> AlbumCoverChoice.Pinned(row.coverUri)
      }
    }

  override suspend fun setAlbumCoverUri(albumKey: String, uri: String) {
    db.albumCoverDao().upsert(
      com.eight87.tonearmboy.data.db.AlbumCoverEntity(albumKey, uri),
    )
  }

  override suspend fun clearAlbumCoverIntentional(albumKey: String) {
    db.albumCoverDao().upsert(
      com.eight87.tonearmboy.data.db.AlbumCoverEntity(albumKey, null),
    )
  }

  override suspend fun resetAlbumCover(albumKey: String) {
    db.albumCoverDao().delete(albumKey)
  }

  /**
   * Cover-art Phase C.5 — looks up the album by [albumKey] in the
   * cached album rollup, then asks the track DAO for the lowest-track
   * file path. Returns null when the album isn't known or has no
   * tracks.
   */
  override suspend fun firstTrackPathForAlbum(albumKey: String): String? {
    val target = albumKey
    val albums = db.albumDao().observeAlbumsWithCounts().first()
    val album = albums.firstOrNull { albumKey(it.name, it.artist) == target } ?: return null
    return db.trackDao().firstByAlbum(album.name, album.artist)?.data
  }

  // R1 — per-track cover overrides. Identical tri-state semantics to
  // album_covers; keyed by trackId so a future track-rename / re-import
  // can invalidate as part of the rescan if the id is recycled.
  override fun trackCoverChoice(trackId: Long): Flow<AlbumCoverChoice> =
    db.trackCoverDao().row(trackId).map { row ->
      when {
        row == null -> AlbumCoverChoice.NoChoice
        row.coverUri.isNullOrBlank() -> AlbumCoverChoice.IntentionallyEmpty
        else -> AlbumCoverChoice.Pinned(row.coverUri)
      }
    }

  override suspend fun setTrackCoverUri(trackId: Long, uri: String) {
    db.trackCoverDao().upsert(
      com.eight87.tonearmboy.data.db.TrackCoverEntity(trackId, uri),
    )
  }

  override suspend fun clearTrackCoverIntentional(trackId: Long) {
    db.trackCoverDao().upsert(
      com.eight87.tonearmboy.data.db.TrackCoverEntity(trackId, null),
    )
  }

  override suspend fun resetTrackCover(trackId: Long) {
    db.trackCoverDao().delete(trackId)
  }

  // R3 — per-artist cover overrides keyed by lowercase trimmed name
  // (`artistKey()`), so casing / whitespace differences across the
  // library don't fragment the override.
  override fun artistCoverChoice(artistName: String): Flow<AlbumCoverChoice> =
    db.artistCoverDao().row(artistKey(artistName)).map { row ->
      when {
        row == null -> AlbumCoverChoice.NoChoice
        row.coverUri.isNullOrBlank() -> AlbumCoverChoice.IntentionallyEmpty
        else -> AlbumCoverChoice.Pinned(row.coverUri)
      }
    }

  override suspend fun setArtistCoverUri(artistName: String, uri: String) {
    db.artistCoverDao().upsert(
      com.eight87.tonearmboy.data.db.ArtistCoverEntity(artistKey(artistName), uri),
    )
  }

  override suspend fun clearArtistCoverIntentional(artistName: String) {
    db.artistCoverDao().upsert(
      com.eight87.tonearmboy.data.db.ArtistCoverEntity(artistKey(artistName), null),
    )
  }

  override suspend fun resetArtistCover(artistName: String) {
    db.artistCoverDao().delete(artistKey(artistName))
  }

  override fun artistsMatching(criteria: FilterCriteria): Flow<List<Artist>> {
    if (criteria.isEmpty()) return observeArtists()
    val tracks = observeTracks()
    val artists = observeArtists()
    return combine(tracks, artists) { tracksList, artistsList ->
      artistsList.filter { artist ->
        val tracksOfArtist = tracksList.filter {
          val a = it.albumArtist?.takeIf { v -> v.isNotBlank() } ?: it.artist
          a.equals(artist.name, ignoreCase = true)
        }
        criteria.matchesArtist(artist, tracksOfArtist)
      }
    }
  }

  override fun genresMatching(criteria: FilterCriteria): Flow<List<Genre>> {
    if (criteria.isEmpty()) return observeGenres()
    val tracks = observeTracks()
    val genres = observeGenres()
    return combine(tracks, genres) { tracksList, genresList ->
      genresList.filter { genre ->
        val tracksOfGenre = tracksList.filter { it.genre.equals(genre.name, ignoreCase = true) }
        criteria.matchesGenre(genre, tracksOfGenre)
      }
    }
  }

  /**
   * Insert or update [tab]. If the tab is new (id == 0), the position
   * defaults to the end of the list when [tab.position] is 0 and a
   * row already exists at that position — preserves "newest goes
   * last" without forcing the caller to query maxPosition first.
   */
  override suspend fun upsertCustomTab(tab: CustomTabEntity): Long {
    val dao = db.customTabDao()
    val resolved = if (tab.id == 0L && tab.position == 0) {
      val next = (dao.maxPosition() ?: -1) + 1
      tab.copy(position = next)
    } else tab
    return dao.upsert(resolved)
  }

  override suspend fun deleteCustomTab(id: Long) {
    db.customTabDao().delete(id)
  }

  override suspend fun reorderCustomTabs(orderedIds: List<Long>) {
    db.customTabDao().reorder(orderedIds)
  }

  // -- Playlist CRUD ---------------------------------------------------------

  override suspend fun createPlaylist(name: String, nowSeconds: Long): Long {
    return db.playlistDao().insert(
      PlaylistEntity(name = name, createdAtSeconds = nowSeconds),
    )
  }

  override suspend fun deletePlaylist(playlistId: Long) {
    db.playlistDao().delete(playlistId)
  }

  override suspend fun renamePlaylist(playlistId: Long, name: String) {
    db.playlistDao().rename(playlistId, name)
  }

  /**
   * D.27.6 — store an optional cover URI for the playlist tile.
   * Pass null to clear (fall back to album-art / letter chain). The
   * caller is responsible for taking a persistable URI permission on
   * SAF-picked images before passing the URI through here.
   */
  override suspend fun setPlaylistCoverUri(playlistId: Long, uri: String?) {
    db.playlistDao().setCoverUri(playlistId, uri)
  }

  override suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
    val dao = db.playlistDao()
    val next = (dao.maxPosition(playlistId) ?: -1) + 1
    dao.insertJoin(
      com.eight87.tonearmboy.data.db.PlaylistTrackEntity(
        playlistId = playlistId,
        trackId = trackId,
        position = next,
      ),
    )
  }

  override suspend fun removeTrackFromPlaylist(playlistId: Long, position: Int) {
    val dao = db.playlistDao()
    val joins = dao.rawJoins(playlistId).filter { it.position != position }
    dao.replaceJoins(playlistId, joins.map { it.trackId })
  }

  override suspend fun reorderPlaylist(playlistId: Long, orderedTrackIds: List<Long>) {
    db.playlistDao().replaceJoins(playlistId, orderedTrackIds)
  }

  // -- Internals -------------------------------------------------------------

  private fun ensureInitialScan() {
    if (initialScanDone.compareAndSet(false, true)) {
      externalScope.launch(Dispatchers.IO) { runScan(initial = true) }
    }
  }

  private suspend fun runScan(initial: Boolean) = scanMutex.withLock {
    // R.A.5 — one-shot reads against the [ScanConfigSource] view. The
    // scan reads the user's latest choice on every pass; toggling the
    // setting at runtime doesn't auto-rescan (surfaced via snackbar
    // copy in the settings UI).
    val separators: Set<String> = scanConfig.multiValueSeparatorTokens.first()
    val scope = scanConfig.musicSourceScope.first()
    val currentConfigHash = computeScanConfigHash(separators, scope)

    // Cold-start fast-path: skip the full scanner walk when both
    // MediaStore tokens AND the scan-config hash match the previous
    // successful scan AND the Room cache isn't empty.
    //
    // Strategy (Android docs — "Detect updates to media files"):
    //   1. `MediaStore.getVersion(context, VOLUME_EXTERNAL)` returns an
    //      opaque token that flips on bulk events (factory reset,
    //      volume mount). Treat any difference as "rescan from scratch."
    //   2. `MediaStore.getGeneration(context, VOLUME_EXTERNAL)` is a
    //      monotonic cursor that increments on every audio insert /
    //      update so long as the version token is stable.
    //   3. The scan-config hash catches user-toggled changes that
    //      affect scan output (separators, music-source scope) — these
    //      don't move MediaStore but DO change what we ingest.
    //
    // Skip is gated on `initial` — explicit rescans (Settings > Rescan
    // music, watcher fires, music-source changes) take the slow path
    // unconditionally because the user is asking for a real check.
    // API < 30 (no version/generation API) always takes the slow path
    // too. The DB-non-empty guard catches the "user cleared app data
    // but DataStore somehow survived" edge case.
    if (initial && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val cachedVersion = scanConfig.cachedMediaStoreVersion()
      val cachedGeneration = scanConfig.cachedMediaStoreGeneration()
      val cachedConfigHash = scanConfig.cachedScanConfigHash()
      val currentVersion = MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL)
      val currentGeneration = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
      val haveCachedTracks = db.trackDao().allIds().isNotEmpty()
      if (cachedVersion != null &&
        cachedVersion == currentVersion &&
        cachedGeneration == currentGeneration &&
        cachedConfigHash == currentConfigHash &&
        haveCachedTracks
      ) {
        Log.i(TAG, "scan(initial): SKIP — MediaStore + config unchanged " +
          "(version=$currentVersion, gen=$currentGeneration)")
        _scanProgress.value = null
        return@withLock
      }
    }

    // D.9d.1 / D.17.3 — when the user has configured one or more music
    // sources AND the mode is FilePicker, restrict the scan to
    // MediaStore rows whose `DATA` path lives under one of the source
    // trees. In System mode we ignore any persisted SAF tree URIs so
    // toggling System wipes the scope without losing the user's saved
    // folder list. (`scope` was hoisted to the skip-check above.)
    val scopePrefixes: Set<String> = if (scope.useFilePicker) {
      scope.treeUris
        .mapNotNull { raw -> SafScopeMapping.treeUriToPathPrefix(Uri.parse(raw)) }
        .toSet()
    } else emptySet()

    // Snapshot the domain Track list so we keep the per-track album
    // ReplayGain values around long enough to fold them into the
    // derived AlbumEntity list. Track entities themselves only store
    // track-level fields; the album-level fields land on AlbumEntity.
    //
    // D.22.1 — throttle the per-track progress callback to ~5 Hz at
    // the producer. The scanner fires this on every track (a 157-track
    // library easily emits 50+/s on a fast disk), and `MutableStateFlow`
    // is conflated for *consumers* but not for *producers*: every
    // assignment still wakes up `collectAsStateWithLifecycle` and forces
    // Compose to recompute the bar's caption + every track row whose
    // surrounding `LazyColumn` recomposes when the `Scaffold` chrome
    // around the bar shifts height. Throttling here keeps the UI
    // thread free without losing user-visible progress fidelity.
    var lastEmit = 0L
    // R.F.4 — scanner returns ScannedTrack (scan-only superset).
    val scanned = try {
      scanner.scanTracks(separators, scopePrefixes) { scanned, total, title ->
        val now = android.os.SystemClock.uptimeMillis()
        if (scanned == total || now - lastEmit >= SCAN_PROGRESS_THROTTLE_MS) {
          lastEmit = now
          _scanProgress.value = ScanProgress(scanned, total, title)
        }
      }
    } finally {
      _scanProgress.value = null
    }
    val snapshot = scanned.map { with(Mapping) { it.toTrackEntity() } }
    val snapshotIds = snapshot.map { it.id }.toHashSet()

    val albumGainPairs: List<Pair<TrackEntity, Pair<Float?, Float?>>> =
      scanned.zip(snapshot).map { (t, e) ->
        e to (t.replayGainAlbumDb to t.replayGainAlbumPeak)
      }

    if (initial) {
      val albums = Mapping.foldAlbumReplayGain(
        Mapping.deriveAlbums(snapshot), albumGainPairs,
      )
      val artists = Mapping.deriveArtistsFromScanned(scanned)
      val genres = Mapping.deriveGenresFromScanned(scanned)
      db.libraryDao().replaceAll(
        com.eight87.tonearmboy.data.db.LibrarySnapshot(snapshot, albums, artists, genres),
      )
      Log.i(TAG, "scan(initial): tracks=${snapshot.size}")
    } else {
      val cachedIds = db.trackDao().allIds().toHashSet()
      val removed = (cachedIds - snapshotIds).toList()
      val upserted = snapshot
      val albums = Mapping.foldAlbumReplayGain(
        Mapping.deriveAlbums(snapshot), albumGainPairs,
      )
      val artists = Mapping.deriveArtistsFromScanned(scanned)
      val genres = Mapping.deriveGenresFromScanned(scanned)
      db.libraryDao().applyDelta(
        com.eight87.tonearmboy.data.db.LibraryDelta(removed, upserted, albums, artists, genres),
      )
      Log.i(TAG, "scan(delta): added/updated=${upserted.size}, removed=${removed.size}")
    }

    // album-art Phase B — folder cover-art scan. Walks the SAF
    // trees the user already granted (Music sources › FilePicker)
    // for adjacent `cover.jpg` / `folder.jpg` / `albumart.jpg` /
    // `front.jpg` files, then maps each scanned album's parent dir
    // basename to the discovered cover URI. Only writes
    // `album_covers` rows for albums where the user has NOT
    // already pinned / cleared a choice (Phase A tri-state
    // `NoChoice` only).
    if (scope.useFilePicker && scope.treeUris.isNotEmpty() &&
      scanConfig.folderCoverScanEnabled.first()
    ) {
      val dirToCover = com.eight87.tonearmboy.data.albumart.FolderArtScanner.walk(
        context,
        scope.treeUris.map { Uri.parse(it) },
      )
      if (dirToCover.isNotEmpty()) {
        val perAlbumCovers = HashMap<String, String>()
        for (t in scanned) {
          val parentDir = java.io.File(t.data).parentFile?.name?.lowercase()?.trim()
          if (parentDir.isNullOrBlank()) continue
          val cover = dirToCover[parentDir] ?: continue
          val key = albumKey(t.album, t.albumArtist ?: t.artist)
          perAlbumCovers.putIfAbsent(key, cover)
        }
        val dao = db.albumCoverDao()
        for ((key, cover) in perAlbumCovers) {
          val existing = dao.row(key).first()
          if (existing == null) {
            dao.upsert(
              com.eight87.tonearmboy.data.db.AlbumCoverEntity(key, cover),
            )
          }
        }
      }
    }

    // Persist the fingerprint AFTER the DB write completes — if the
    // scan crashed midway we'd rather rescan next launch than skip on
    // a stale-but-cached generation cursor. API <30 doesn't expose
    // the tokens; we just leave the cache empty so next launch falls
    // through to the slow path.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val v = MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL)
      val g = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
      scanConfig.setMediaStoreCache(v, g, currentConfigHash)
      Log.i(TAG, "scan: cached MediaStore fingerprint (version=$v, gen=$g, cfg=$currentConfigHash)")
    }
  }

  /**
   * Stable hash of the inputs that affect scanner output but DON'T
   * move MediaStore's version/generation tokens — multi-value
   * separators (split tags differently) + music-source scope (System
   * vs FilePicker, and the SAF tree URIs that bound the scan).
   *
   * The hash is encoded as a hex digest of `separators + scope` so it
   * round-trips through DataStore as a String. Sets are sorted before
   * hashing so the digest is order-independent.
   */
  private fun computeScanConfigHash(
    separators: Set<String>,
    scope: MusicSourceScope,
  ): String {
    val sortedSeparators = separators.toSortedSet().joinToString(",")
    val sortedTrees = scope.treeUris.toSortedSet().joinToString(",")
    val payload = "sep=$sortedSeparators|fp=${scope.useFilePicker}|trees=$sortedTrees"
    return Integer.toHexString(payload.hashCode())
  }

  fun shutdown() {
    mediaObserver.close()
  }

  companion object {
    private const val TAG = "tonearmboy-library"
    private const val RESCAN_DEBOUNCE_MS = 750L

    /**
     * D.22.1 — minimum spacing between per-track progress emissions on
     * [scanProgress]. 200 ms = ~5 Hz, the slowest cadence that still
     * looks live to a human watching the bar advance. Below this the
     * UI thread spends real time recomposing the caption "Scanning
     * 17 of 157 · …" on every track on a fast disk.
     */
    internal const val SCAN_PROGRESS_THROTTLE_MS = 200L

    /**
     * Build an artist list at query time, with optional collaborator
     * filtering. Visible for tests.
     *
     * The shape is deliberately the same as the cached
     * `observeArtistsWithCounts` query: one row per artist, with album
     * + track counts. We assign synthetic positive ids based on the
     * stable sort order so the UI's `key = it.id` stays stable across
     * Flow emissions.
     */
    internal fun deriveArtistsFromTracks(
      tracks: List<TrackEntity>,
      hideCollaborators: Boolean,
    ): List<Artist> {
      // Build a map artist-name -> (set of album names, track count).
      val acc = LinkedHashMap<String, Pair<HashSet<String>, Int>>()
      for (t in tracks) {
        val primary = (t.albumArtist?.takeIf { it.isNotBlank() }) ?: t.artist
        val names = if (hideCollaborators) {
          listOfNotNull(primary?.takeIf { it.isNotBlank() })
        } else {
          // Include both album-artist and artist when they differ.
          val secondary = t.artist?.takeIf {
            it.isNotBlank() && !it.equals(primary, ignoreCase = true)
          }
          listOfNotNull(primary?.takeIf { it.isNotBlank() }, secondary)
        }
        for (name in names) {
          val (albums, count) = acc[name] ?: (HashSet<String>() to 0)
          val nextAlbums = albums.also { t.album?.let(it::add) }
          acc[name] = nextAlbums to (count + 1)
        }
      }
      // Sort case-insensitively by name; assign deterministic ids by
      // hashing the name into a positive Long (stable across emissions).
      return acc
        .toSortedMap(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        .entries
        .map { (name, agg) ->
          val (albums, count) = agg
          Artist(
            id = (name.hashCode().toLong() and 0xFFFFFFFFL),
            name = name,
            albumCount = albums.size,
            trackCount = count,
          )
        }
    }
  }
}
