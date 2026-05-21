package com.eight87.tonearmboy.theme

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.eight87.tonearmboy.AppGraph
import com.eight87.tonearmboy.data.AlbumCoverChoice
import com.eight87.tonearmboy.data.albumKey
import com.eight87.tonearmboy.data.albumart.AlbumArtFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * D.20.4 — runtime palette source. The activity feeds it the
 * playing track id + MediaStore album id; this class resolves the
 * best cover URI (per-track override → per-album override → legacy
 * MediaStore albumart URI), loads the bitmap, runs
 * `extractAlbumPalette`, and publishes both the palette and the
 * resolved URI on `StateFlow`s the Compose tree consumes through
 * `LocalAlbumPalette` (palette) and the album-art background layer
 * (cover URI).
 *
 * Caches per-resolved-URI palettes (LRU, 32 entries) so a track
 * switch inside the same album doesn't re-decode the bitmap.
 *
 * Falls back to [AlbumPalette.Empty] / null URI when no cover source
 * exists anywhere for the playing track.
 */
class AlbumPaletteSource(private val context: Context) {

  private val _palette = MutableStateFlow(AlbumPalette.Empty)
  val palette: StateFlow<AlbumPalette> = _palette.asStateFlow()

  private val _coverUri = MutableStateFlow<String?>(null)
  /**
   * Resolved cover URI for the playing track. `null` when no cover
   * source exists. Consumed by the fullscreen blurred-background
   * layer so it can paint the same art the lockscreen + chrome tint
   * see.
   */
  val coverUri: StateFlow<String?> = _coverUri.asStateFlow()

  private val cache = LruCache<String, AlbumPalette>(32)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  /**
   * Update the palette + background URI to reflect the now-playing
   * track. Cheap when the resolved URI matches the previous call;
   * the LRU cache makes second-and-subsequent visits to the same
   * cover free.
   */
  fun setNowPlaying(trackId: Long?, albumId: Long?) {
    if (trackId == null && albumId == null) {
      _palette.value = AlbumPalette.Empty
      _coverUri.value = null
      return
    }
    scope.launch {
      val resolved = withContext(Dispatchers.IO) {
        resolveCoverUri(trackId, albumId)
      }
      _coverUri.value = resolved
      if (resolved == null) {
        _palette.value = AlbumPalette.Empty
        return@launch
      }
      cache[resolved]?.let {
        _palette.value = it
        return@launch
      }
      val extracted = withContext(Dispatchers.IO) {
        loadBitmap(resolved)?.let { bmp ->
          try {
            extractAlbumPalette(bmp)
          } finally {
            // Recycle the decoded bitmap as soon as the palette has
            // been derived. Cover art bitmaps are 600 ^ 2 ARGB_8888
            // (~1.4 MB) and we'd otherwise hold them until GC.
            bmp.recycle()
          }
        } ?: AlbumPalette.Empty
      }
      cache.put(resolved, extracted)
      _palette.value = extracted
    }
  }

  /**
   * Cascade through the same cover-source chain the BitmapLoader
   * uses for the lockscreen widget. Per-track pin wins, then
   * per-album pin, then the legacy MediaStore albumart URI as a
   * last resort.
   *
   * Caller MUST run this on a non-Main dispatcher (Room reads block).
   */
  private suspend fun resolveCoverUri(trackId: Long?, albumId: Long?): String? {
    val graph = AppGraph.get(context)
    if (trackId != null) {
      val choice = graph.tracks.trackCoverChoice(trackId).first()
      if (choice is AlbumCoverChoice.Pinned &&
        AlbumArtFetcher.pinnedFileStillExists(choice.uri)
      ) return choice.uri
      val track = graph.tracks.trackById(trackId)
      if (track != null) {
        val key = albumKey(track.album, track.albumArtist ?: track.artist)
        val albumChoice = graph.albums.albumCoverChoice(key).first()
        if (albumChoice is AlbumCoverChoice.Pinned &&
          AlbumArtFetcher.pinnedFileStillExists(albumChoice.uri)
        ) return albumChoice.uri
      }
    }
    return albumId?.let {
      ContentUris.withAppendedId(LEGACY_ALBUM_ART_BASE, it).toString()
    }
  }

  fun shutdown() {
    scope.cancel()
  }

  /**
   * Decode the resolved cover URI to a bitmap. Returns null when the
   * URI is missing or undecodable. Pure I/O — caller MUST run this
   * on a non-Main dispatcher.
   */
  private fun loadBitmap(uri: String): Bitmap? {
    val parsed = Uri.parse(uri)
    return runCatching {
      context.contentResolver.openInputStream(parsed).use { input ->
        if (input == null) return null
        BitmapFactory.decodeStream(input)
      }
    }.onFailure {
      Log.d(TAG, "loadBitmap failed for uri=$uri", it)
    }.getOrNull()
  }

  companion object {
    private const val TAG = "tonearmboy-palette"
    private val LEGACY_ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")
  }
}
