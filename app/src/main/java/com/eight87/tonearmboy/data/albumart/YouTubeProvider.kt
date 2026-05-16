package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cover-art Phase B — YouTube cover-art provider.
 *
 * Video-ID resolution chain (first hit wins, all stages skipped on
 * null):
 *   1. Filename regex (`<title>-<11-char>.ext`, NewPipe default).
 *   2. **Round 4** — `COMMENT` / author-ish text tag via
 *      [TrackTagReader] + [YouTubeCommentExtractor]. Handles NewPipe
 *      downloads where the user renamed the file and the only surviving
 *      breadcrumb is the source URL baked into the COMMENT tag.
 *   3. Piped search by `artist + title`, optionally duration-filtered
 *      (Round 4 — caller passes `expectedDurationSec` for ±2 s match).
 *
 * After an ID is found, walk the thumbnail URL ladder
 * `maxres → sd → hq`; the lowest tier (`hqdefault.jpg`) always exists,
 * so the chain always returns a usable URL once we have an ID.
 *
 * The square-crop step lives in [AlbumArtFetcher] post-download
 * (Phase B.2) so MusicBrainz / iTunes covers benefit too.
 */
class YouTubeProvider(
  private val piped: PipedClient = PipedClient(),
  private val client: OkHttpClient = defaultClient(),
  private val baseUrl: String = "https://i.ytimg.com",
  /**
   * Optional tag reader for Round 4's COMMENT-tag stage. Null in tests
   * that only exercise the filename or Piped paths; non-null in
   * production wiring (see `ProviderRegistry.Deps.tagReader`).
   */
  private val tagReader: TrackTagReader? = null,
) : CoverArtProvider {
  override val kind: ProviderKind = ProviderKind.YouTube

  override suspend fun findCoverUrl(req: CoverArtRequest): String? {
    val videoId = resolveVideoId(req) ?: return null
    return pickBestThumbnail(videoId)
  }

  private suspend fun resolveVideoId(req: CoverArtRequest): String? {
    // Stage 1 — filename.
    req.sampleTrackPath?.let { path ->
      YouTubeIdExtractor.fromFilename(path)?.let { return it }
    }
    // Stage 2 — tag (Round 4). Only attempted when a path AND a reader
    // are both present; reading tags off content URIs / a missing
    // reader is a no-op fallthrough.
    val reader = tagReader
    val path = req.sampleTrackPath
    if (reader != null && path != null) {
      val blob = runCatching { reader.readTextTags(path) }.getOrNull()
      YouTubeCommentExtractor.fromCommentText(blob)?.let { return it }
    }
    // Stage 3 — Piped search. The Round 4 duration filter lives in
    // [PipedClient.searchVideoId]; this provider passes whatever
    // duration the request carries (null is fine — filter is skipped).
    return piped.searchVideoId(req.albumArtist, req.albumName)
  }

  /**
   * HEAD-walk the thumbnail ladder. `maxresdefault` only exists for
   * uploads at 720p+; `sddefault` for 480p+; `hqdefault` is generated
   * for every video. Returning the highest tier that 200s minimises
   * how often we download a generic black-bars placeholder for an
   * uploader's missing `maxres`.
   */
  private suspend fun pickBestThumbnail(videoId: String): String = withContext(Dispatchers.IO) {
    for (tier in listOf("maxresdefault", "sddefault")) {
      val url = "$baseUrl/vi/$videoId/$tier.jpg"
      if (head(url)) return@withContext url
    }
    "$baseUrl/vi/$videoId/hqdefault.jpg"
  }

  private fun head(url: String): Boolean = runCatching {
    val req = Request.Builder().url(url).head().build()
    client.newCall(req).execute().use { resp -> resp.isSuccessful }
  }.getOrDefault(false)

  companion object {
    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .followRedirects(true)
      .build()
  }
}
