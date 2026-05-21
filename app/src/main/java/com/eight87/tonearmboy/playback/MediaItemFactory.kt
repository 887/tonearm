package com.eight87.tonearmboy.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.eight87.tonearmboy.data.model.Track

/**
 * R.C.6 — `Track → MediaItem` extension extracted from
 * `PlaybackUiController` so the file is ~30 lines lighter and the
 * conversion logic lives next to the playback layer's other Media3
 * adapters.
 */
internal fun Track.toMediaItem(): MediaItem {
  // Phase E.1 / E.2: feed the MediaSession enough metadata for the
  // System UI notification + lock-screen surface to render properly.
  // We point artworkUri at the file URI of the audio file itself —
  // Media3's `DataSourceBitmapLoader` will fall back to extracting the
  // embedded ID3v2 / FLAC picture frame when the URI resolves to an
  // audio file. Tracks without embedded art simply render no large
  // icon, which is the same fallback the platform notification uses.
  val fileUri = Uri.parse("file://${data}")
  // D.15.7 — also stash the MediaStore album id so the in-app
  // NowPlaying surface can drive the same legacy-albumart Coil
  // request the library tabs do (cheaper + already cached).
  // Always stash the track id so the BitmapLoader can resolve a pinned
  // cover (per-track / per-album) from the LibraryRepository when the
  // file itself carries no embedded picture frame.
  val extras = android.os.Bundle().apply {
    putLong(PlaybackUiController.EXTRA_TRACK_ID, id)
    if (mediaStoreAlbumId != null) {
      putLong(PlaybackUiController.EXTRA_MEDIA_STORE_ALBUM_ID, mediaStoreAlbumId)
    }
  }
  val metadata = MediaMetadata.Builder()
    .setTitle(title)
    .setArtist(artist)
    .setAlbumTitle(album)
    .setAlbumArtist(albumArtist)
    .setArtworkUri(fileUri)
    .setExtras(extras)
    .build()
  return MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(fileUri)
    .setMediaMetadata(metadata)
    .build()
}
