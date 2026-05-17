package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

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
  /**
   * Round 5 — minimum interval (ms) between two consecutive requests
   * to the same Piped host. Default 1000 ms keeps us at ~1 req/sec/
   * instance, the courtesy floor Piped operators ask for. Lowering
   * this risks 429s / temp-bans across the public pool.
   */
  private val perHostMinIntervalMs: Long = 1000L,
) {
  private val json = Json { ignoreUnknownKeys = true }
  private val lastGood = AtomicReference<String?>(null)

  /**
   * Round 5 — per-host last-request wall-clock for the throttle.
   * `ConcurrentHashMap` so the worker (single-threaded today, but
   * defensive for any future concurrent fetcher) is safe.
   */
  private val lastRequestMs: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

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
    // Round 6 / Fix A — coerce `<unknown>` artist + strip emoji /
    // noise qualifiers / underscores from the title before encoding.
    val q = TitleSanitizer.buildQuery(artist, title)
    if (q.isBlank()) return@withContext null
    val encoded = java.net.URLEncoder.encode(q, "UTF-8")

    // Try last-good first (cheap optimisation across consecutive
    // album lookups in one bulk pass); on miss, walk the pool in
    // declaration order and update the cached pointer on success.
    val order = listOfNotNull(lastGood.get()) + instances.filter { it != lastGood.get() }
    for (host in order) {
      // Round 5 — per-host courtesy delay. The first request to a host
      // goes through immediately; subsequent ones wait so we never
      // exceed ~1 req/sec/instance across the bulk worker's track loop.
      throttle(host)
      val url = "$host/search?q=$encoded&filter=music_songs"
      val attempt = runCatching { search(url, expectedDurationSec) }
      val err = attempt.exceptionOrNull()
      if (err is ThrottledException) {
        // Re-thrown to the YouTube provider → chain, which adds
        // YouTube to the run-scoped throttled set. The bulk worker
        // skips Piped lookups for the remainder of the pass.
        throw err
      }
      val id = attempt.getOrNull()
      if (id != null) {
        lastGood.set(host)
        return@withContext id
      }
    }
    null
  }

  /**
   * Round 6 / Fix C — richer search that returns the cleaned query,
   * the result count, the matched id (or null), and the duration
   * mismatch (signed seconds) of the picked result, all rolled into
   * a [StageDiag.PipedSearch]. The bulk-art log uses this to render
   * `piped: "Some Title" → 7 results (id abcDEFghi12, +1 s)`.
   *
   * Artist is coerced to null via [TitleSanitizer.coerceArtist] (the
   * MediaStore `<unknown>` placeholder turns into a title-only search).
   * The title is run through [TitleSanitizer.cleanTitle].
   */
  suspend fun searchRich(
    artist: String?,
    title: String,
    expectedDurationSec: Int? = null,
  ): StageDiag.PipedSearch = withContext(Dispatchers.IO) {
    val query = TitleSanitizer.buildQuery(artist, title)
    if (query.isBlank()) {
      return@withContext StageDiag.PipedSearch(
        query = query,
        results = 0,
        matchedId = null,
        durationMismatchSec = null,
      )
    }
    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
    val order = listOfNotNull(lastGood.get()) + instances.filter { it != lastGood.get() }
    var lastResults = 0
    var lastMismatch: Int? = null
    var lastHost: String? = null
    for (host in order) {
      throttle(host)
      val url = "$host/search?q=$encoded&filter=music_songs"
      val attempt = runCatching { searchHostRich(url, expectedDurationSec) }
      val err = attempt.exceptionOrNull()
      if (err is ThrottledException) throw err
      val hit = attempt.getOrNull()
      if (hit != null) {
        lastResults = hit.results
        lastMismatch = hit.durationMismatchSec
        lastHost = host
        if (hit.matchedId != null) {
          lastGood.set(host)
          return@withContext hit.copy(query = query, host = host)
        }
      }
    }
    StageDiag.PipedSearch(
      query = query,
      results = lastResults,
      matchedId = null,
      durationMismatchSec = lastMismatch,
      host = lastHost ?: "unreachable: ${order.joinToString(",") { it.substringAfter("://").substringBefore('/') }}",
    )
  }

  /**
   * Round 6 / Fix C — per-call diagnostic info (result count + match
   * + duration mismatch). Returns null when the host was unreachable
   * / non-success so the caller can walk to the next instance.
   */
  private fun searchHostRich(url: String, expectedDurationSec: Int?): StageDiag.PipedSearch? {
    val req = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .build()
    client.newCall(req).execute().use { resp ->
      if (resp.code == 429 || resp.code == 403) {
        throw ThrottledException("Piped responded ${resp.code} from $url")
      }
      if (!resp.isSuccessful) return null
      val body = resp.body?.string() ?: return null
      val parsed = json.decodeFromString<PipedSearchResponse>(body)
      val streamItems = parsed.items.filter { it.type == null || it.type == "stream" }
      if (streamItems.isEmpty()) {
        return StageDiag.PipedSearch(query = "", results = 0, matchedId = null, durationMismatchSec = null)
      }
      val picked = pickByDuration(streamItems, expectedDurationSec)
      val id = extractVideoId(picked.url)
      val mismatch = if (expectedDurationSec != null && picked.duration != null) {
        picked.duration - expectedDurationSec
      } else null
      return StageDiag.PipedSearch(
        query = "",
        results = streamItems.size,
        matchedId = id,
        durationMismatchSec = mismatch,
      )
    }
  }

  /**
   * Sleep until at least [perHostMinIntervalMs] has elapsed since the
   * previous request to [host]. Updates the per-host timestamp before
   * returning so back-to-back callers serialise.
   */
  private suspend fun throttle(host: String) {
    val now = System.currentTimeMillis()
    val last = lastRequestMs[host] ?: 0L
    val elapsed = now - last
    val wait = max(0L, perHostMinIntervalMs - elapsed)
    if (wait > 0) delay(wait)
    lastRequestMs[host] = System.currentTimeMillis()
  }

  private fun search(url: String, expectedDurationSec: Int?): String? {
    val req = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .build()
    client.newCall(req).execute().use { resp ->
      // Round 5 — propagate rate-limit / forbid signals so the chain
      // can disable this provider for the remainder of the bulk run.
      if (resp.code == 429 || resp.code == 403) {
        throw ThrottledException("Piped responded ${resp.code} from $url")
      }
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

    /**
     * Starter pool. Walked in order; first reachable wins.
     *
     * Round 8 (2026-05-17): the public Piped federation has collapsed.
     * Of the prior pool, three are DNS-gone (`r4fo.com`,
     * `privacydev.net`) or returning 502 (`kavin.rocks`), and
     * `adminforge.de` redirects to a broken host. The only public
     * instance currently serving `/search` with `200 OK` and valid
     * JSON is `api.piped.private.coffee`. Verified manually against
     * `?q=Clawz+SG+Flux&filter=music_songs` on 2026-05-17.
     *
     * `piped-instances.kavin.rocks` is the canonical liveness oracle
     * — when this list rots again, re-derive from there.
     */
    val DEFAULT_PIPED_INSTANCES: List<String> = listOf(
      "https://api.piped.private.coffee",
    )

    private val YT_ID_ALPHABET = Regex("[A-Za-z0-9_-]{11}")

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .followRedirects(true)
      .build()
  }
}
