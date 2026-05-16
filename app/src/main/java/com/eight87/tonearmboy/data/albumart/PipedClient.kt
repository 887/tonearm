package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Cover-art Phase B — Piped search client.
 *
 * Public Piped instances rotate; hardcoding one is brittle. We ship a
 * starter pool, walk it on failure, and cache the last-good instance
 * in-memory for the process lifetime so subsequent lookups skip dead
 * hosts.
 *
 * Persisted nothing — instance health changes day to day. The pool
 * itself is user-overridable via Settings → Content → Cover art
 * providers → YouTube → Piped instances (Phase D.3).
 *
 * Round 4 — search results can be duration-filtered. When the caller
 * passes [expectedDurationSec], items within ±2 s win over the raw top
 * hit. If the filter empties the list we fall back to the unfiltered
 * top result so the no-match case never degrades.
 */
class PipedClient(
  private val instances: List<String> = DEFAULT_PIPED_INSTANCES,
  private val client: OkHttpClient = defaultClient(),
) {
  private val json = Json { ignoreUnknownKeys = true }
  private val lastGood = AtomicReference<String?>(null)

  /**
   * Search Piped for the music-songs track matching [artist] + [title].
   * Returns the YouTube video ID of the top result (optionally
   * duration-filtered), or null when no instance reachable / no match.
   */
  suspend fun searchVideoId(
    artist: String?,
    title: String,
    expectedDurationSec: Int? = null,
  ): String? = withContext(Dispatchers.IO) {
    val q = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title)
      .joinToString(" ")
      .ifBlank { return@withContext null }
    val encoded = java.net.URLEncoder.encode(q, "UTF-8")

    // Try last-good first (cheap optimisation across consecutive
    // album lookups in one bulk pass); on miss, walk the pool in
    // declaration order and update the cached pointer on success.
    val order = listOfNotNull(lastGood.get()) + instances.filter { it != lastGood.get() }
    for (host in order) {
      val url = "$host/search?q=$encoded&filter=music_songs"
      val id = runCatching { search(url, expectedDurationSec) }.getOrNull()
      if (id != null) {
        lastGood.set(host)
        return@withContext id
      }
    }
    null
  }

  private fun search(url: String, expectedDurationSec: Int?): String? {
    val req = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .build()
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) return null
      val body = resp.body?.string() ?: return null
      val parsed = json.decodeFromString<PipedSearchResponse>(body)
      val streamItems = parsed.items.filter { it.type == null || it.type == "stream" }
      if (streamItems.isEmpty()) return null

      val picked = pickByDuration(streamItems, expectedDurationSec)
      return extractVideoId(picked.url)
    }
  }

  /**
   * Round 4 — choose a stream item by duration affinity:
   *  - If [expectedDurationSec] is null, return the top hit unchanged.
   *  - If at least one item is within ±2 s, return the first such item.
   *  - Otherwise return the top hit (no degradation vs old behaviour).
   *
   * Logs the outcome at debug for AVD/device verification.
   */
  internal fun pickByDuration(items: List<PipedItem>, expectedDurationSec: Int?): PipedItem {
    if (expectedDurationSec == null) return items.first()
    val tolerated = items.filter { item ->
      val d = item.duration ?: return@filter false
      abs(d - expectedDurationSec) <= DURATION_TOLERANCE_SEC
    }
    return if (tolerated.isEmpty()) {
      logD("piped filter: 0 matches within ±${DURATION_TOLERANCE_SEC}s of ${expectedDurationSec}s — falling back to top hit")
      items.first()
    } else {
      val chosen = tolerated.first()
      logD("piped filter: ${tolerated.size}/${items.size} matches → ${chosen.url} (dur ${chosen.duration}s, target ${expectedDurationSec}s)")
      chosen
    }
  }

  /**
   * Log helper that swallows `Method not mocked` from `android.util.Log`
   * when called from plain JVM tests (PipedClientTest is not Robolectric).
   * On a real Android process this delegates to `Log.d` as expected.
   */
  private fun logD(message: String) {
    runCatching { android.util.Log.d(TAG, message) }
  }

  internal fun extractVideoId(url: String?): String? {
    if (url.isNullOrBlank()) return null
    // Piped's URL field is sometimes a path like "/watch?v=<id>" and
    // sometimes a full https URL. Cover both.
    val tail = url.substringAfter("v=", missingDelimiterValue = "")
      .substringBefore('&')
    if (tail.isNotEmpty() && YT_ID_ALPHABET.matches(tail)) return tail
    // Fallback — last 11-char segment shaped like a YouTube ID.
    val raw = url.toHttpUrlOrNull()
    val path = raw?.encodedPath ?: url
    val seg = path.substringAfterLast('/')
    return if (YT_ID_ALPHABET.matches(seg)) seg else null
  }

  @Serializable
  internal data class PipedSearchResponse(
    val items: List<PipedItem> = emptyList(),
  )

  @Serializable
  internal data class PipedItem(
    val url: String? = null,
    val type: String? = null,
    /**
     * Round 4 — Piped returns the video duration in whole seconds for
     * stream items. Nullable because non-stream items omit it (and
     * defensively for protocol drift).
     */
    val duration: Int? = null,
  )

  companion object {
    private const val TAG = "PipedClient"
    private const val DURATION_TOLERANCE_SEC: Int = 2

    /** Starter pool. Walked in order; first reachable wins. */
    val DEFAULT_PIPED_INSTANCES: List<String> = listOf(
      "https://pipedapi.kavin.rocks",
      "https://pipedapi.adminforge.de",
      "https://pipedapi.r4fo.com",
      "https://api.piped.privacydev.net",
    )

    private val YT_ID_ALPHABET = Regex("[A-Za-z0-9_-]{11}")

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .followRedirects(true)
      .build()
  }
}
