package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cover-art Phase B — YouTube cover-art provider.
 *
 * Strategy:
 *   1. Extract YouTube video ID from the sample track's filename
 *      (NewPipe pattern). Zero network calls.
 *   2. If no ID found, fall back to a Piped search by artist + title.
 *   3. Walk thumbnail URL ladder `maxres → sd → hq`; the lowest tier
 *      (`hqdefault.jpg`) always exists, so the chain always returns
 *      a usable URL once we have an ID.
 *
 * The square-crop step lives in [AlbumArtFetcher] post-download
 * (Phase B.2) so MusicBrainz / iTunes covers benefit too.
 */
class YouTubeProvider(
  private val piped: PipedClient = PipedClient(),
  private val client: OkHttpClient = defaultClient(),
  private val baseUrl: String = "https://i.ytimg.com",
) : CoverArtProvider {
  override val kind: ProviderKind = ProviderKind.YouTube

  override suspend fun findCoverUrl(req: CoverArtRequest): String? {
    val videoId = req.sampleTrackPath?.let { YouTubeIdExtractor.fromFilename(it) }
      ?: piped.searchVideoId(req.albumArtist, req.albumName)
      ?: return null
    return pickBestThumbnail(videoId)
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
