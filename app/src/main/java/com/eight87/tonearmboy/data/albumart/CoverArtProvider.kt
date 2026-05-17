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
)

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
  ): ProviderResult? {
    for (p in providers) {
      if (p.kind in throttled) continue
      val result = runCatching { p.findCover(req) }
      val value = result.getOrNull()
      if (value != null) return value
      // Throttling: caller's set tracks the providers that hit a 429
      // / 403 this run, so subsequent track lookups skip them outright.
      val err = result.exceptionOrNull()
      if (err is ThrottledException) {
        throttled.add(p.kind)
      }
    }
    return null
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
