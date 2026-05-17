package com.eight87.tonearmboy.data.albumart

/**
 * Cover-art Phase A — sealed-class provider abstraction.
 *
 * Replaces the hardcoded `when (service)` branch in [AlbumArtFetcher]
 * with a pluggable strategy. Each variant fetches a single candidate
 * cover URL or returns null on miss; [ProviderChain] iterates the
 * configured list in user priority order until the first non-null
 * result.
 */
// Not `sealed` — test fakes live in `app/src/test` which is a separate
// Kotlin module and Kotlin prohibits extending a sealed interface from
// another module. The closed-set guarantee we wanted from `sealed`
// comes instead from [ProviderKind] (a real enum) + [ProviderRegistry]
// being the single construction point.
interface CoverArtProvider {
  val kind: ProviderKind

  /**
   * Legacy entry point — returns the cover URL string only. Kept for
   * existing tests + callers that don't care which stage of the chain
   * fired. Production code goes through [findCover] which returns the
   * richer [ProviderResult] used by the bulk-art log.
   */
  suspend fun findCoverUrl(req: CoverArtRequest): String? = findCover(req)?.url

  /**
   * Round 5 — preferred entry point. Returns a [ProviderResult]
   * carrying the URL, the originating stage ([ResolutionSource]), and
   * any extra diagnostic context (the YouTube video id when known) so
   * the bulk-art log can surface "Saved from YouTube (COMMENT tag)" vs
   * "Saved from YouTube (Piped search)" etc.
   *
   * Default impl wraps [findCoverUrl] in a [ResolutionSource.Direct]
   * result so providers that don't need stage attribution (iTunes /
   * MusicBrainz, one network round-trip each) need no override.
   */
  suspend fun findCover(req: CoverArtRequest): ProviderResult? =
    findCoverUrl(req)?.let { ProviderResult(kind, it, ResolutionSource.Direct) }
}

/**
 * Round 5 — sub-stage attribution for a provider hit. Each provider
 * documents which values it can emit:
 *
 *  - [YouTubeProvider] — [Filename], [CommentTag], or [PipedSearch]
 *  - [ITunesProvider] / [MusicBrainzProvider] — [Direct] (single
 *    network round-trip, no internal staging worth surfacing)
 */
enum class ResolutionSource { Filename, CommentTag, PipedSearch, Direct }

/**
 * Round 5 — provider hit payload. Wider than the legacy `String?` so
 * the bulk-art log can name the exact stage that fired ("Saved from
 * YouTube (COMMENT tag)") and expose the video id when known —
 * diagnostic gold for the user debugging "why didn't this hit".
 */
data class ProviderResult(
  val kind: ProviderKind,
  val url: String,
  val source: ResolutionSource,
  /** YouTube video id when the chain went through a YouTube stage; null otherwise. */
  val videoId: String? = null,
  /**
   * Round 6 / Fix C — per-stage diagnostics collected as the provider
   * walked its internal chain. Surfaced in the bulk-art log row as a
   * monospace sub-block so a screenshot tells the full story without
   * the user pulling a file off the device.
   */
  val diags: List<StageDiag> = emptyList(),
)

/**
 * Round 6 / Fix C — per-stage diagnostic info attached to a
 * [ProviderResult] (or — for misses — to a [LogEntry] directly).
 *
 * Each stage variant records what that stage actually did:
 * the matched id (or null), the captured input slice, the bytes
 * scanned, the search query, the result count, etc. The log row
 * renders each diag as a single short line.
 */
sealed interface StageDiag {
  /** Stage 1 — filename regex. */
  data class Filename(val matched: String?) : StageDiag

  /**
   * Stage 2 — COMMENT-tag byte scan.
   *
   * [captured] is the URL slice the scanner pulled out of the file
   * bytes (or null when nothing was found). [matched] is the parsed
   * 11-char YouTube id. [bytesScanned] is the total bytes inspected
   * across head + tail regions.
   */
  data class CommentTag(
    val captured: String?,
    val matched: String?,
    val bytesScanned: Int,
  ) : StageDiag

  /**
   * Stage 3 — Piped search.
   *
   * [query] is the actual cleaned query string sent to Piped (post
   * [TitleSanitizer]). [results] is how many search items came back.
   * [matchedId] is the chosen 11-char id. [durationMismatchSec] is
   * the picked result's duration minus the requested duration (sign
   * indicates over/under), or null when no duration filter was applied.
   */
  data class PipedSearch(
    val query: String,
    val results: Int,
    val matchedId: String?,
    val durationMismatchSec: Int? = null,
    /**
     * Round 10 — which Piped host actually answered (or `"unreachable"`
     * when every instance in the pool failed). Surfaced in the log so
     * the user can diagnose stale-instance settings without pulling
     * preferences off the device.
     */
    val host: String? = null,
  ) : StageDiag
}

/**
 * Known provider variants. Persisted by enum name in
 * [com.eight87.tonearmboy.ui.settings.SettingsRepository] — adding a
 * new variant requires no migration; existing rows are canonicalised
 * by [ProviderListCodec] to include the new kind with `enabled = false`.
 */
enum class ProviderKind {
  YouTube,
  MusicBrainz,
  ITunes,
}

/**
 * Single resolution attempt.
 *
 * [sampleTrackPath] is the absolute filesystem path of one
 * representative track from the album, or null when unavailable. The
 * YouTube provider's filename-pattern fast path needs this to extract
 * an embedded video ID without firing any network requests.
 */
data class CoverArtRequest(
  val albumName: String,
  val albumArtist: String?,
  val sampleTrackPath: String?,
  val musicBrainzMinScore: Int = 70,
  /**
   * Round 4 — expected track duration in whole seconds, when known.
   * Piped's `/search` results include a `duration` field; when this is
   * supplied the search client filters to results within ±2 s, then
   * falls back to the unfiltered top result if filtering empties the
   * list. Null when the caller doesn't know (album-level fetches,
   * legacy single-service path).
   */
  val expectedDurationSec: Int? = null,
  /**
   * Round 7 — per-track title. Populated by the per-track fetch path;
   * null for album-level requests. YouTubeProvider prefers this over
   * [albumName] for Piped search because NewPipe writes a generic
   * `album = "Music"` on every YouTube download, which would otherwise
   * make every per-track search collapse to the literal query `Music`.
   */
  val trackTitle: String? = null,
)

/**
 * Resolver — walks providers in priority order, returning the first
 * non-null URL and the kind that produced it.
 *
 * Failure of one provider never short-circuits the chain: a thrown
 * exception is swallowed (via [runCatching]) and treated as a miss, so
 * a network blip in one provider can't kill the cascade.
 */
class ProviderChain(private val providers: List<CoverArtProvider>) {
  suspend fun resolve(req: CoverArtRequest): Pair<ProviderKind, String>? =
    resolveRich(req)?.let { it.kind to it.url }

  /**
   * Round 5 — resolve returning the richer [ProviderResult] so callers
   * (the bulk-art worker) can name the stage that fired in the log.
   *
   * Per-provider exceptions are still swallowed (a transient error in
   * one provider doesn't kill the cascade), with one new wrinkle: a
   * provider that throws [ThrottledException] is removed from the
   * chain for the *remainder of the run* via [throttled] so we don't
   * keep hammering a host that just 429'd us. The chain itself stays
   * stateless; the caller (worker) owns the throttled set.
   */
  suspend fun resolveRich(
    req: CoverArtRequest,
    throttled: MutableSet<ProviderKind> = mutableSetOf(),
  ): ProviderResult? = resolveRichWithMissDiags(req, throttled).first

  /**
   * Round 6 / Fix C — resolve returning both the hit (if any) and the
   * collected miss-diagnostics from each provider that returned null.
   * Used by the bulk-art worker so the no-match log row can surface
   * `filename: no ID / tag-scan: no URL / piped: "x" → 0 results`.
   *
   * On hit, the hit's own diags ride on the returned [ProviderResult];
   * the second list is the diags from any *prior* providers that
   * missed before this one fired. Today only [YouTubeProvider]
   * actually emits miss-diags; the others return an empty list via
   * the default-empty `lastMissDiags`.
   */
  suspend fun resolveRichWithMissDiags(
    req: CoverArtRequest,
    throttled: MutableSet<ProviderKind> = mutableSetOf(),
  ): Pair<ProviderResult?, List<StageDiag>> {
    val missDiags = mutableListOf<StageDiag>()
    for (p in providers) {
      if (p.kind in throttled) continue
      val result = runCatching { p.findCover(req) }
      val value = result.getOrNull()
      if (value != null) return value to missDiags.toList()
      // Provider missed — collect any per-stage diagnostics it stashed.
      if (p is YouTubeProvider) {
        missDiags += p.lastMissDiags
        p.clearMissDiags()
      }
      // Throttling: caller's set tracks the providers that hit a 429
      // / 403 this run, so subsequent track lookups skip them outright.
      val err = result.exceptionOrNull()
      if (err is ThrottledException) {
        throttled.add(p.kind)
      }
    }
    return null to missDiags.toList()
  }
}

/**
 * Round 5 — signalled by a provider when an upstream service replies
 * with HTTP 429 / 403. The chain catches it and marks the provider as
 * throttled for the rest of the bulk run; subsequent track lookups
 * skip it instead of hammering the same host. See
 * [PipedClient.searchVideoId] for the per-instance back-off, and
 * [com.eight87.tonearmboy.data.albumart.AlbumArtBulkWorker.doWork]
 * for the run-scoped set.
 */
class ThrottledException(message: String) : RuntimeException(message)
