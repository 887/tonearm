package com.eight87.tonearmboy.data.albumart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.eight87.tonearmboy.data.AlbumCoverChoice
import com.eight87.tonearmboy.data.AlbumSource
import com.eight87.tonearmboy.data.TrackSource
import com.eight87.tonearmboy.data.albumKey
import com.eight87.tonearmboy.data.model.Track
import com.eight87.tonearmboy.ui.settings.CoverArtService
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * album-art Phase D — orchestrator.
 *
 * Resolves an album to a Cover Art Archive image and stores the local
 * file URI in `album_covers` so the existing Phase A pipeline (Coil
 * via `CoverArt(coverUriOverride = ...)`) renders it.
 *
 * **Phase A precedence is preserved:**
 *
 *   - When the user already pinned a cover (`AlbumCoverChoice.Pinned`),
 *     we never overwrite it.
 *   - When the user explicitly cleared a cover
 *     (`AlbumCoverChoice.IntentionallyEmpty`), we treat that as
 *     "user has spoken — don't auto-fetch" and skip.
 *   - We only overwrite [AlbumCoverChoice.NoChoice] rows.
 *
 * The downloaded image lands in the app's private cache so we don't
 * need WRITE permission on shared storage.
 */
class AlbumArtFetcher(
  private val albumSource: AlbumSource,
  private val trackSource: TrackSource? = null,
  private val musicBrainz: MusicBrainzClient = MusicBrainzClient(),
  private val iTunes: ITunesClient = ITunesClient(),
) {
  private val downloader: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

  /**
   * Resolve [albumName] / [albumArtist] to a cover image via the
   * caller-selected [service], download it to the app's private
   * cache, and pin it as the album's cover.
   *
   * **Privacy contract:** when [service] is [CoverArtService.Disabled]
   * this function makes ZERO web requests and returns
   * [FetchResult.ServiceDisabled]. Both the bulk auto-fetch worker
   * and the per-album manual "Search online" overflow funnel through
   * here, so the Disabled setting is the single switch that gates
   * every cover-art network round-trip.
   *
   * Returns:
   *   - [FetchResult.Saved] when a new cover was pinned.
   *   - [FetchResult.AlreadyPinned] / [FetchResult.IntentionallyEmpty]
   *     when the user has spoken and we left the album alone.
   *   - [FetchResult.NotFound] when the chosen service has no match.
   *   - [FetchResult.ServiceDisabled] when [service] is
   *     [CoverArtService.Disabled] — no requests fired.
   *   - [FetchResult.Failed] on network / IO errors.
   */
  @Deprecated(
    "Use the ProviderChain overload (Phase C). This single-service path " +
      "wraps the legacy enum in a one-element chain and is kept for one " +
      "release so in-flight callers compile.",
    ReplaceWith(""),
  )
  suspend fun fetch(
    context: Context,
    albumName: String,
    albumArtist: String?,
    service: CoverArtService,
    musicBrainzMinScore: Int = 70,
    overwriteUserChoice: Boolean = false,
  ): FetchResult {
    if (service == CoverArtService.Disabled) return FetchResult.ServiceDisabled
    val chain = when (service) {
      CoverArtService.Disabled -> return FetchResult.ServiceDisabled
      CoverArtService.MusicBrainz -> ProviderChain(listOf(MusicBrainzProvider(musicBrainz)))
      CoverArtService.ITunes -> ProviderChain(listOf(ITunesProvider(iTunes)))
    }
    return fetch(
      context = context,
      albumName = albumName,
      albumArtist = albumArtist,
      sampleTrackPath = null,
      chain = chain,
      musicBrainzMinScore = musicBrainzMinScore,
      overwriteUserChoice = overwriteUserChoice,
    )
  }

  /**
   * Phase A.4 / C.4 entry point — resolve an album to a cover via the
   * caller-supplied [chain] and pin it locally.
   *
   * The chain may be empty (caller decided no providers are active) —
   * we return [FetchResult.ServiceDisabled] without firing any
   * requests, preserving the privacy contract of the old `Disabled`
   * single-service value.
   */
  suspend fun fetch(
    context: Context,
    albumName: String,
    albumArtist: String?,
    sampleTrackPath: String?,
    chain: ProviderChain,
    musicBrainzMinScore: Int = 70,
    overwriteUserChoice: Boolean = false,
  ): FetchResult {
    val key = albumKey(albumName, albumArtist)
    if (!overwriteUserChoice) {
      when (albumSource.albumCoverChoice(key).first()) {
        is AlbumCoverChoice.Pinned -> return FetchResult.AlreadyPinned
        AlbumCoverChoice.IntentionallyEmpty -> return FetchResult.IntentionallyEmpty
        AlbumCoverChoice.NoChoice -> Unit
      }
    }
    return AlbumArtFetchRegistry.withFetch(key) {
      doFetch(
        context = context,
        key = key,
        req = CoverArtRequest(
          albumName = albumName,
          albumArtist = albumArtist,
          sampleTrackPath = sampleTrackPath,
          musicBrainzMinScore = musicBrainzMinScore,
        ),
        chain = chain,
      )
    }
  }

  private suspend fun doFetch(
    context: Context,
    key: String,
    req: CoverArtRequest,
    chain: ProviderChain,
  ): FetchResult {
    val resolved = chain.resolve(req) ?: return FetchResult.NotFound
    val coverUrl = resolved.second
    val providerKind = resolved.first

    val cacheDir = File(context.cacheDir, "album_art").also { it.mkdirs() }
    val target = File(cacheDir, "${key.hashCode().toUInt()}.jpg")
    val request = Request.Builder().url(coverUrl).build()
    val downloaded = runCatching {
      downloader.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) return@use false
        val body = resp.body ?: return@use false
        target.outputStream().use { out -> body.byteStream().copyTo(out) }
        true
      }
    }.getOrDefault(false)
    if (!downloaded) return FetchResult.Failed("download")

    // Phase B.2 — square-crop pass. Benefits every provider (YouTube
    // thumbnails are 16:9, but iTunes' rare landscape promo art and
    // MusicBrainz' occasional letterbox case also normalise). Tolerant
    // 5% aspect window: covers already square (or close) are left
    // untouched to avoid a wasteful decode/re-encode round-trip.
    cropToSquareIfNeeded(target)

    albumSource.setAlbumCoverUri(key, target.toURI().toString())
    return FetchResult.Saved(target.toURI().toString(), providerKind)
  }

  /**
   * Round 3 — per-track cover fetch.
   *
   * Resolves a cover for [track] via the same provider [chain], using
   * the track's own filename for the YouTube embedded-ID fast path
   * (NewPipe stores its 11-char video id in the filename), and pins
   * the result in `track_covers` via [trackSource] instead of
   * `album_covers`. The pre-existing per-album path is left alone —
   * the per-album manual "Search online" flow + user-pinned album
   * covers still use `fetch(...)`.
   *
   * Precedence (mirrors the per-album path):
   *   - `TrackCoverChoice.Pinned`            → returns [FetchResult.AlreadyPinned]
   *   - `TrackCoverChoice.IntentionallyEmpty` → returns [FetchResult.IntentionallyEmpty]
   *   - `TrackCoverChoice.NoChoice`           → resolve + download + pin
   */
  suspend fun fetchTrack(
    context: Context,
    track: Track,
    chain: ProviderChain,
    musicBrainzMinScore: Int = 70,
    overwriteUserChoice: Boolean = false,
  ): FetchResult {
    val sink = trackSource
      ?: return FetchResult.Failed("fetchTrack called with no TrackSource wired")
    if (!overwriteUserChoice) {
      when (sink.trackCoverChoice(track.id).first()) {
        is AlbumCoverChoice.Pinned -> return FetchResult.AlreadyPinned
        AlbumCoverChoice.IntentionallyEmpty -> return FetchResult.IntentionallyEmpty
        AlbumCoverChoice.NoChoice -> Unit
      }
    }
    val key = "track-${track.id}"
    return AlbumArtFetchRegistry.withFetch(key) {
      val req = CoverArtRequest(
        // The album/artist fields feed the Piped-search fallback when
        // the filename has no embedded YouTube id. Track title isn't
        // a CoverArtRequest field; we keep the search shape identical
        // to the album path so each provider's chain hits the same
        // signatures.
        albumName = track.album.orEmpty().ifBlank { track.title },
        albumArtist = track.albumArtist ?: track.artist,
        sampleTrackPath = track.data,
        musicBrainzMinScore = musicBrainzMinScore,
      )
      val resolved = chain.resolve(req) ?: return@withFetch FetchResult.NotFound
      val coverUrl = resolved.second
      val providerKind = resolved.first

      val cacheDir = File(context.cacheDir, "track_art").also { it.mkdirs() }
      val target = File(cacheDir, "${track.id}.jpg")
      val request = Request.Builder().url(coverUrl).build()
      val downloaded = runCatching {
        downloader.newCall(request).execute().use { resp ->
          if (!resp.isSuccessful) return@use false
          val body = resp.body ?: return@use false
          target.outputStream().use { out -> body.byteStream().copyTo(out) }
          true
        }
      }.getOrDefault(false)
      if (!downloaded) return@withFetch FetchResult.Failed("download")

      cropToSquareIfNeeded(target)
      sink.setTrackCoverUri(track.id, target.toURI().toString())
      FetchResult.Saved(target.toURI().toString(), providerKind)
    }
  }

  /**
   * Decode [file], centre-crop to a square if the source aspect ratio
   * is outside ±5% of 1:1, re-encode JPEG quality 85, overwrite [file].
   * On any decode / encode error, leave the file alone — the original
   * download is still better than nothing.
   */
  internal fun cropToSquareIfNeeded(file: File) {
    runCatching {
      val src = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching
      val w = src.width
      val h = src.height
      if (w == 0 || h == 0) return@runCatching
      val aspect = w.toFloat() / h.toFloat()
      if (abs(aspect - 1f) <= ASPECT_TOLERANCE) {
        src.recycle()
        return@runCatching
      }
      val side = min(w, h)
      val x = (w - side) / 2
      val y = (h - side) / 2
      val cropped = Bitmap.createBitmap(src, x, y, side, side)
      if (cropped !== src) src.recycle()
      file.outputStream().use { out ->
        cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
      }
      cropped.recycle()
    }
  }

  sealed interface FetchResult {
    /**
     * [providerKind] names the chain entry whose URL was downloaded.
     * `null` for the legacy single-service fetch path when it
     * succeeded via a non-chain code path (none exist today, kept for
     * forward compatibility).
     */
    data class Saved(val uri: String, val providerKind: ProviderKind? = null) : FetchResult
    data object AlreadyPinned : FetchResult
    data object IntentionallyEmpty : FetchResult
    data object NotFound : FetchResult
    data object ServiceDisabled : FetchResult
    data class Failed(val reason: String) : FetchResult
  }
}

/** Convenience type alias for screens that consume the fetch result. */
typealias FetchResult = AlbumArtFetcher.FetchResult

private const val ASPECT_TOLERANCE: Float = 0.05f
private const val JPEG_QUALITY: Int = 85
