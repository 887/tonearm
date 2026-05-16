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
  suspend fun findCoverUrl(req: CoverArtRequest): String?
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
  suspend fun resolve(req: CoverArtRequest): Pair<ProviderKind, String>? {
    for (p in providers) {
      val url = runCatching { p.findCoverUrl(req) }.getOrNull() ?: continue
      return p.kind to url
    }
    return null
  }
}
