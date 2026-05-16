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
 * **Triggering:** the user enables "Auto-discover missing album art"
 * or taps "Fill in missing covers now" in Settings › Cover art. The
 * bridge wires that to `WorkManager.enqueueUniqueWork` with a one-shot
 * [androidx.work.OneTimeWorkRequest].
 *
 * **Visibility (Round 2 / Ask A):** the worker writes every album
 * attempt to [AlbumArtBulkProgress] — a `Running` heartbeat on entry
 * and a terminal `Hit` / `Miss` / `Skipped` / `Error` on exit. The
 * Settings progress sub-page subscribes to that StateFlow so the user
 * watches live progress instead of staring at a silent UI after
 * tapping "Start now".
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
    if (settings.coverArtDisabled.flow.first()) {
      AlbumArtBulkProgress.reset(totalAlbums = 0)
      AlbumArtBulkProgress.append(
        AlbumArtBulkProgress.LogEntry(
          timestampMs = System.currentTimeMillis(),
          albumName = "—",
          albumArtist = null,
          providerKind = null,
          outcome = AlbumArtBulkProgress.Outcome.Skipped,
          note = "Online lookups disabled — flip the kill switch off to run.",
        ),
      )
      AlbumArtBulkProgress.finish()
      return Result.success()
    }

    val configs = settings.coverArtProviders.flow.first()
    if (configs.none { it.enabled }) {
      AlbumArtBulkProgress.reset(totalAlbums = 0)
      AlbumArtBulkProgress.append(
        AlbumArtBulkProgress.LogEntry(
          timestampMs = System.currentTimeMillis(),
          albumName = "—",
          albumArtist = null,
          providerKind = null,
          outcome = AlbumArtBulkProgress.Outcome.Skipped,
          note = "No providers enabled — turn on at least one in Cover art providers.",
        ),
      )
      AlbumArtBulkProgress.finish()
      return Result.success()
    }

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

    // Albums that actually need a lookup — anything with embedded art
    // or a pre-existing user choice is filtered up-front so the progress
    // counter mirrors what the user perceives as "in flight".
    val candidates = albums.filter { it.mediaStoreAlbumId == null }

    AlbumArtBulkProgress.reset(totalAlbums = candidates.size)

    try {
      for (album in candidates) {
        if (isStopped) break
        val key = albumKey(album.name, album.artist)
        val choice = graph.albums.albumCoverChoice(key).first()
        if (choice !is AlbumCoverChoice.NoChoice) {
          AlbumArtBulkProgress.append(
            AlbumArtBulkProgress.LogEntry(
              timestampMs = System.currentTimeMillis(),
              albumName = album.name,
              albumArtist = album.artist,
              providerKind = null,
              outcome = AlbumArtBulkProgress.Outcome.Skipped,
              note = when (choice) {
                is AlbumCoverChoice.Pinned -> "Already pinned"
                AlbumCoverChoice.IntentionallyEmpty -> "Marked intentionally empty"
                else -> "Already has a cover"
              },
            ),
          )
          continue
        }

        // Heartbeat: surface the in-flight album so the user sees
        // *something* happen even when the chain is mid-rate-limit.
        AlbumArtBulkProgress.append(
          AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = album.name,
            albumArtist = album.artist,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Running,
            note = "Looking up…",
          ),
        )

        val samplePath = graph.albums.firstTrackPathForAlbum(key)
        val result = try {
          fetcher.fetch(
            context = applicationContext,
            albumName = album.name,
            albumArtist = album.artist,
            sampleTrackPath = samplePath,
            chain = chain,
            musicBrainzMinScore = mbMinScore,
          )
        } catch (t: Throwable) {
          AlbumArtBulkProgress.append(
            AlbumArtBulkProgress.LogEntry(
              timestampMs = System.currentTimeMillis(),
              albumName = album.name,
              albumArtist = album.artist,
              providerKind = null,
              outcome = AlbumArtBulkProgress.Outcome.Error,
              note = t.message ?: t::class.simpleName,
            ),
          )
          continue
        }

        val entry = when (result) {
          is AlbumArtFetcher.FetchResult.Saved -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = album.name,
            albumArtist = album.artist,
            providerKind = result.providerKind,
            outcome = AlbumArtBulkProgress.Outcome.Hit,
            note = result.providerKind?.let { "Saved from ${labelFor(it)}" },
          )
          AlbumArtFetcher.FetchResult.NotFound -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = album.name,
            albumArtist = album.artist,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Miss,
            note = "No provider returned a match",
          )
          AlbumArtFetcher.FetchResult.ServiceDisabled -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = album.name,
            albumArtist = album.artist,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Skipped,
            note = "Service disabled",
          )
          is AlbumArtFetcher.FetchResult.Failed -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = album.name,
            albumArtist = album.artist,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Error,
            note = result.reason,
          )
          AlbumArtFetcher.FetchResult.AlreadyPinned -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = album.name,
            albumArtist = album.artist,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Skipped,
            note = "Already pinned",
          )
          AlbumArtFetcher.FetchResult.IntentionallyEmpty -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = album.name,
            albumArtist = album.artist,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Skipped,
            note = "Marked intentionally empty",
          )
        }
        AlbumArtBulkProgress.append(entry)
      }
    } finally {
      AlbumArtBulkProgress.finish()
    }
    return Result.success()
  }

  companion object {
    const val UNIQUE_WORK_NAME = "tonearmboy_album_art_bulk"

    /** Human-readable label for a [ProviderKind] — used in log notes. */
    fun labelFor(kind: ProviderKind): String = when (kind) {
      ProviderKind.YouTube -> "YouTube"
      ProviderKind.ITunes -> "iTunes"
      ProviderKind.MusicBrainz -> "MusicBrainz"
    }
  }
}
