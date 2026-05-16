package com.eight87.tonearmboy.data.albumart

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eight87.tonearmboy.AppGraph
import com.eight87.tonearmboy.data.AlbumCoverChoice
import kotlinx.coroutines.flow.first

/**
 * album-art Phase D — bulk auto-fetch worker.
 *
 * **Round 3 pivot:** the worker now walks **every song in the library**,
 * not every album. Each track is looked up via its own filename (the
 * YouTube provider's embedded-ID fast path is per-video) or its title/
 * artist (Piped-search fallback), so NewPipe-downloaded YouTube tracks —
 * which all share an arbitrary "Music" album in MediaStore — each get
 * the correct thumbnail of their own video. Per-album pinning still
 * trumps everything via [AlbumArtFetcher.fetchTrack]'s precedence check
 * on the per-track choice row.
 *
 * Triggering, kill-switch semantics, and the [AlbumArtBulkProgress]
 * sink are unchanged from Round 2.
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
      AlbumArtBulkProgress.reset(totalTracks = 0)
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
      AlbumArtBulkProgress.reset(totalTracks = 0)
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
    val fetcher = AlbumArtFetcher(
      albumSource = graph.albums,
      trackSource = graph.tracks,
    )

    val tracks = graph.tracks.observeTracks().first()
    AlbumArtBulkProgress.reset(totalTracks = tracks.size)

    try {
      for (track in tracks) {
        if (isStopped) break

        val choice = graph.tracks.trackCoverChoice(track.id).first()
        if (choice !is AlbumCoverChoice.NoChoice) {
          AlbumArtBulkProgress.append(
            AlbumArtBulkProgress.LogEntry(
              timestampMs = System.currentTimeMillis(),
              albumName = track.album.orEmpty(),
              albumArtist = track.albumArtist ?: track.artist,
              trackTitle = track.title,
              providerKind = null,
              outcome = AlbumArtBulkProgress.Outcome.Skipped,
              note = when (choice) {
                is AlbumCoverChoice.Pinned -> "Already pinned"
                AlbumCoverChoice.IntentionallyEmpty -> "Marked intentionally empty"
                AlbumCoverChoice.NoChoice -> "Already has a cover"
              },
            ),
          )
          continue
        }

        // Heartbeat — per-song progress so the user sees activity even
        // when the chain is mid-rate-limit.
        AlbumArtBulkProgress.append(
          AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Running,
            note = "Looking up…",
          ),
        )

        val result = try {
          fetcher.fetchTrack(
            context = applicationContext,
            track = track,
            chain = chain,
            musicBrainzMinScore = mbMinScore,
          )
        } catch (t: Throwable) {
          AlbumArtBulkProgress.append(
            AlbumArtBulkProgress.LogEntry(
              timestampMs = System.currentTimeMillis(),
              albumName = track.album.orEmpty(),
              albumArtist = track.albumArtist ?: track.artist,
              trackTitle = track.title,
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
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            providerKind = result.providerKind,
            outcome = AlbumArtBulkProgress.Outcome.Hit,
            note = result.providerKind?.let { "Saved from ${labelFor(it)}" },
          )
          AlbumArtFetcher.FetchResult.NotFound -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Miss,
            note = "No provider returned a match",
          )
          AlbumArtFetcher.FetchResult.ServiceDisabled -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Skipped,
            note = "Service disabled",
          )
          is AlbumArtFetcher.FetchResult.Failed -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Error,
            note = result.reason,
          )
          AlbumArtFetcher.FetchResult.AlreadyPinned -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Skipped,
            note = "Already pinned",
          )
          AlbumArtFetcher.FetchResult.IntentionallyEmpty -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
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
