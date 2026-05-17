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
    val deps = ProviderRegistry.Deps(
      piped = PipedClient(instances = pipedInstances),
      // Round 4 — Android-backed COMMENT-tag reader for the YouTube
      // provider's second-stage ID lookup. NewPipe writes the source
      // URL into COMMENT; filename-renamed downloads find their ID
      // here when stage 1 (filename regex) misses.
      tagReader = AndroidTrackTagReader(applicationContext),
    )
    val chain = ProviderRegistry.buildChain(configs, deps)

    val mbMinScore = settings.coverArtMatchScore.flow.first()
    val fetcher = AlbumArtFetcher(
      albumSource = graph.albums,
      trackSource = graph.tracks,
    )

    val tracks = graph.tracks.observeTracks().first()
    AlbumArtBulkProgress.reset(totalTracks = tracks.size)

    // Round 5 — run-scoped set of providers that 429'd / 403'd during
    // this bulk pass. The chain skips any provider in here on
    // subsequent track lookups, so a temp-banned Piped instance can't
    // cause N more wasted round-trips across the rest of the library.
    val throttled: MutableSet<ProviderKind> = mutableSetOf()

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
              trackId = track.id,
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
        // when the chain is mid-rate-limit. Round 6 / Fix B: same
        // trackId as the eventual terminal entry, so the terminal
        // update replaces this row in place instead of appending.
        AlbumArtBulkProgress.append(
          AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            trackId = track.id,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Running,
            note = "Looking up…",
          ),
        )

        val throttledBefore = throttled.toSet()
        val result = try {
          fetcher.fetchTrack(
            context = applicationContext,
            track = track,
            chain = chain,
            musicBrainzMinScore = mbMinScore,
            throttled = throttled,
          )
        } catch (t: Throwable) {
          AlbumArtBulkProgress.append(
            AlbumArtBulkProgress.LogEntry(
              timestampMs = System.currentTimeMillis(),
              albumName = track.album.orEmpty(),
              albumArtist = track.albumArtist ?: track.artist,
              trackTitle = track.title,
              trackId = track.id,
              providerKind = null,
              outcome = AlbumArtBulkProgress.Outcome.Error,
              note = t.message ?: t::class.simpleName,
            ),
          )
          continue
        }

        // Round 5 — surface any newly-throttled provider so the user
        // sees the back-off in the log instead of an unexplained Miss.
        val newlyThrottled = throttled - throttledBefore
        newlyThrottled.forEach { kind ->
          AlbumArtBulkProgress.append(
            AlbumArtBulkProgress.LogEntry(
              timestampMs = System.currentTimeMillis(),
              albumName = track.album.orEmpty(),
              albumArtist = track.albumArtist ?: track.artist,
              trackTitle = track.title,
              providerKind = kind,
              outcome = AlbumArtBulkProgress.Outcome.Throttled,
              note = "${labelFor(kind)} throttled (429/403) — skipping for the rest of the run",
            ),
          )
        }

        val entry = when (result) {
          is AlbumArtFetcher.FetchResult.Saved -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            trackId = track.id,
            providerKind = result.providerKind,
            outcome = AlbumArtBulkProgress.Outcome.Hit,
            note = noteForSaved(result),
            source = result.source,
            videoId = result.videoId,
            diags = result.diags,
          )
          is AlbumArtFetcher.FetchResult.NotFound -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            trackId = track.id,
            providerKind = null,
            // YouTube-specific phrasing when the chain has only YouTube
            // and it returned nothing — the user reads "No YouTube
            // video found" as a clearer signal than the generic
            // "No provider returned a match".
            outcome = if (configs.singleEnabledIsYouTube()) {
              AlbumArtBulkProgress.Outcome.NoIdResolved
            } else AlbumArtBulkProgress.Outcome.Miss,
            note = if (configs.singleEnabledIsYouTube()) "No YouTube video found"
            else "No provider returned a match",
            diags = result.diags,
          )
          AlbumArtFetcher.FetchResult.ServiceDisabled -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            trackId = track.id,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Skipped,
            note = "Service disabled",
          )
          is AlbumArtFetcher.FetchResult.Failed -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            trackId = track.id,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Error,
            note = result.reason,
          )
          AlbumArtFetcher.FetchResult.AlreadyPinned -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            trackId = track.id,
            providerKind = null,
            outcome = AlbumArtBulkProgress.Outcome.Skipped,
            note = "Already pinned",
          )
          AlbumArtFetcher.FetchResult.IntentionallyEmpty -> AlbumArtBulkProgress.LogEntry(
            timestampMs = System.currentTimeMillis(),
            albumName = track.album.orEmpty(),
            albumArtist = track.albumArtist ?: track.artist,
            trackTitle = track.title,
            trackId = track.id,
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

    /**
     * Round 5 — short label for a sub-stage that produced a hit:
     * `"filename"`, `"COMMENT tag"`, `"Piped search"`, or no tag at
     * all for [ResolutionSource.Direct] (iTunes / MusicBrainz —
     * single-stage, naming the stage adds noise without info).
     */
    internal fun labelFor(src: ResolutionSource?): String? = when (src) {
      ResolutionSource.Filename -> "filename"
      ResolutionSource.CommentTag -> "COMMENT tag"
      ResolutionSource.PipedSearch -> "Piped search"
      ResolutionSource.Direct, null -> null
    }

    /**
     * Build the "Saved from YouTube (Piped search)" note shape from a
     * [FetchResult.Saved]. The sub-stage is parenthesised; iTunes /
     * MusicBrainz hits collapse to plain "Saved from iTunes" since
     * their single-stage attribution adds nothing.
     */
    internal fun noteForSaved(saved: AlbumArtFetcher.FetchResult.Saved): String? {
      val provider = saved.providerKind?.let { labelFor(it) } ?: return null
      val stage = labelFor(saved.source)
      val base = "Saved from $provider"
      return if (stage != null) "$base ($stage)" else base
    }

    /**
     * True when the user has exactly one provider enabled and it's
     * YouTube. Used to swap "No YouTube video found" in for the
     * generic "No provider returned a match" — clearer signal when
     * YouTube is the sole chain entry.
     */
  }
}

/**
 * Round 5 — top-level extension so the in-line `when` inside
 * [AlbumArtBulkWorker.doWork] can read `configs.singleEnabledIsYouTube()`
 * without qualifying the companion. True only when exactly one
 * provider is enabled and it's YouTube — drives the "No YouTube video
 * found" phrasing for the chain-empty case.
 */
internal fun List<ProviderConfig>.singleEnabledIsYouTube(): Boolean {
  val enabled = filter { it.enabled }
  return enabled.size == 1 && enabled.first().kind == ProviderKind.YouTube
}
