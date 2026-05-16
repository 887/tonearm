package com.eight87.tonearmboy.data.albumart

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eight87.tonearmboy.AppGraph
import com.eight87.tonearmboy.data.AlbumCoverChoice
import com.eight87.tonearmboy.data.albumKey
import kotlinx.coroutines.flow.first

/**
 * album-art Phase D — bulk auto-fetch worker.
 *
 * Walks every album in the cached library, asks the
 * [AlbumArtFetcher] to fill in covers for albums with no user choice
 * AND no MediaStore embedded art. Phase A's `IntentionallyEmpty`
 * sentinel is respected — those albums are skipped.
 *
 * **Triggering:** the user enables "Auto-discover missing album art
 * (MusicBrainz)" in Settings › Content. The bridge wires that toggle
 * to `WorkManager.enqueueUniqueWork` with a one-shot
 * [androidx.work.OneTimeWorkRequest] (no periodic schedule — the
 * bulk pass runs ONCE per toggle-on; subsequent rescans don't
 * re-invoke it). On the next library rescan, the toggle's "is on"
 * state is consulted again to decide whether to rerun.
 *
 * **Rate:** [MusicBrainzClient] enforces 1 req/sec serialised, so
 * even a 100-album library takes ~3 minutes. The worker runs as a
 * background-data-sync task (no foreground notification) since the
 * pace is slow and the user already opted-in via the toggle.
 */
class AlbumArtBulkWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val graph = AppGraph.get(applicationContext)
    val settings = graph.settingsRepository

    // Cover-art Phase E.2 — privacy kill switch is the single
    // early-exit gate. When enabled the worker fires zero web
    // requests; provider preferences are preserved for whenever the
    // user flips the kill switch back off.
    if (settings.coverArtDisabled.flow.first()) return Result.success()

    val configs = settings.coverArtProviders.flow.first()
    if (configs.none { it.enabled }) return Result.success()

    val pipedInstancesRaw = settings.pipedInstances.flow.first()
    val pipedInstances = pipedInstancesRaw
      .split(',')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .ifEmpty { PipedClient.DEFAULT_PIPED_INSTANCES }
    val deps = ProviderRegistry.Deps(piped = PipedClient(instances = pipedInstances))
    val chain = ProviderRegistry.buildChain(configs, deps)

    val mbMinScore = settings.coverArtMatchScore.flow.first()
    val fetcher = AlbumArtFetcher(graph.albums)
    val albums = graph.albums.observeAlbums().first()

    var saved = 0
    var skipped = 0
    var failed = 0
    for (album in albums) {
      if (isStopped) break
      if (album.mediaStoreAlbumId != null) {
        skipped++
        continue
      }
      val key = albumKey(album.name, album.artist)
      val choice = graph.albums.albumCoverChoice(key).first()
      if (choice !is AlbumCoverChoice.NoChoice) {
        skipped++
        continue
      }
      val samplePath = graph.albums.firstTrackPathForAlbum(key)
      val result = fetcher.fetch(
        context = applicationContext,
        albumName = album.name,
        albumArtist = album.artist,
        sampleTrackPath = samplePath,
        chain = chain,
        musicBrainzMinScore = mbMinScore,
      )
      when (result) {
        is AlbumArtFetcher.FetchResult.Saved -> saved++
        AlbumArtFetcher.FetchResult.NotFound,
        AlbumArtFetcher.FetchResult.ServiceDisabled,
        is AlbumArtFetcher.FetchResult.Failed -> failed++
        AlbumArtFetcher.FetchResult.AlreadyPinned,
        AlbumArtFetcher.FetchResult.IntentionallyEmpty -> skipped++
      }
    }
    return Result.success()
  }

  companion object {
    const val UNIQUE_WORK_NAME = "tonearmboy_album_art_bulk"
  }
}
