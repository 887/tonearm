package com.eight87.tonearmboy.ui.library

import android.content.ContentUris
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import com.eight87.tonearmboy.data.TrackSource
import com.eight87.tonearmboy.data.albumKey
import com.eight87.tonearmboy.data.albumart.AlbumArtFetchRegistry
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode

/**
 * Phase D.9b.3 — render the cover art for an album, with a music-note
 * placeholder when the load fails or the user has covers turned off.
 *
 * The album art URI is the legacy MediaStore `albumart` content path:
 *
 *   content://media/external/audio/albumart/<albumId>
 *
 * That path is supported on API 26+ (it predates scoped storage) and
 * still works on API 36 — Android resolves it through MediaStore's
 * built-in album-art thumbnail provider. The post-API-29 alternative,
 * `ContentResolver.loadThumbnail(albumUri, size, signal)`, exists but
 * is awkward to drive from a Compose async image — Coil already
 * handles the legacy URI directly via `ContentResolver.openInputStream`.
 *
 * Behaviour by [mode]:
 *  - [AlbumCoversMode.Off] — render the placeholder, no network/disk
 *    work at all
 *  - [AlbumCoversMode.Balanced] (default) — Coil scales the request
 *    to the cell `size` so disk + decode cost matches the visible
 *    cell, not the full-resolution embedded image
 *  - [AlbumCoversMode.On] — Coil decodes at the original size
 */
@Composable
fun CoverArt(
  albumId: Long?,
  size: Dp,
  mode: AlbumCoversMode,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
  /**
   * Phase A — per-album cover override URI. When non-null, Coil
   * loads this directly (skipping the legacy MediaStore `albumart`
   * path). Pass null to keep the existing fallback chain. Pulled
   * from `LibraryRepository.albumCoverUri` upstream.
   */
  coverUriOverride: String? = null,
  /**
   * When [albumName] (and optionally [albumArtist]) are supplied,
   * CoverArt subscribes to [AlbumArtFetchRegistry] for the album
   * key and renders a "fetching from web" indicator (cloud icon +
   * spinner) when an `AlbumArtFetcher.fetch` call is in flight for
   * that album. Without this the user couldn't tell the difference
   * between "no cover, idle" and "no cover, currently downloading".
   * Callers that don't know the album metadata (e.g. the playlist
   * tile that only has a MediaStore id) just leave these null.
   */
  albumName: String? = null,
  albumArtist: String? = null,
  /**
   * Round 5 — when the cell represents an individual song and
   * [trackSource] is wired, CoverArt subscribes to
   * [TrackSource.trackCoverUriFlow] and prefers any pinned per-track
   * cover URI (set by the user or by the bulk-art worker) over
   * [coverUriOverride] and the album fallback. Cascade is:
   *
   *   pinned-track > pinned-album (`coverUriOverride`) > album-art > placeholder
   *
   * `null` `trackId` keeps the legacy album-level behaviour intact for
   * the Albums / Artists / Genres / Playlists tabs.
   */
  trackId: Long? = null,
  trackSource: TrackSource? = null,
) {
  Box(
    modifier = modifier
      .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    // Web-fetch indicator state — independent of Coil's local load
    // state. When AlbumArtFetcher is currently fetching for this
    // album's key, render the cloud-download icon + spinner overlay
    // so the user can see "we're trying" instead of staring at the
    // empty placeholder.
    val fetching = if (albumName != null) {
      val key = remember(albumName, albumArtist) { albumKey(albumName, albumArtist) }
      val keys by AlbumArtFetchRegistry.inFlight.collectAsStateWithLifecycle()
      key in keys
    } else false

    // Round 5 — per-track cover override takes precedence over the
    // album fallback when the cell represents a song. Collected here
    // (before the placeholder branch) so a pinned track cover lights
    // up the tile even when the song has no MediaStore albumId at all.
    val perTrackUri: String? = if (trackId != null && trackSource != null) {
      val flow = remember(trackId) { trackSource.trackCoverUriFlow(trackId) }
      val state by flow.collectAsStateWithLifecycle(initialValue = null as String?)
      state?.takeIf { it.isNotBlank() }
    } else null

    val showPlaceholder = mode == AlbumCoversMode.Off ||
      (albumId == null && coverUriOverride.isNullOrBlank() && perTrackUri.isNullOrBlank())
    if (showPlaceholder) {
      if (fetching) FetchingIndicator(size) else Placeholder(size)
      return@Box
    }

    val context = LocalContext.current
    val uri: Any = remember(albumId, coverUriOverride, perTrackUri) {
      perTrackUri?.takeIf { it.isNotBlank() }
        ?: coverUriOverride?.takeIf { it.isNotBlank() }
        ?: albumArtUri(albumId ?: 0L)
    }
    // Perf — we only need to react to the Error phase (to flip the
    // overlay back to the music-note placeholder when the local
    // lookup fails). Loading is left as the flat surfaceVariant
    // background — pending cells show as colored squares, image
    // pops in when ready (Aves-style). Dropping the per-cell
    // CircularProgressIndicator removes the compose/measure/draw
    // cost that was dominating Songs-tab scroll.
    var isError by remember(uri) { mutableStateOf(false) }

    val request = remember(uri) {
      // Coil derives size from layout.
      val key = "cover-$uri"
      ImageRequest.Builder(context)
        .data(uri)
        .precision(Precision.INEXACT)
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .build()
    }

    // Always render the AsyncImage so it can fire load events; render
    // overlays on top only for Error and the web-fetch state.
    AsyncImage(
      model = request,
      contentDescription = contentDescription,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
      onState = { state ->
        isError = state is AsyncImagePainter.State.Error
      },
    )

    // Overlay rules:
    //   - Fetching from web: cloud-download icon + spinner takes
    //     precedence — the user sees the network activity.
    //   - Error: music-note placeholder (replaces partial image with
    //     the canonical "no art" symbol).
    //   - Otherwise: nothing on top. While Coil is loading the cell
    //     shows the flat surfaceVariant background, image pops in
    //     once decoded.
    when {
      fetching -> FetchingIndicator(size)
      isError -> Placeholder(size)
      else -> Unit
    }
  }
}

/**
 * `AlbumArtFetcher` is currently making a web request for this album.
 * Render a cloud-download icon + small spinner so the user sees the
 * network-active state distinctly from "empty placeholder, idle".
 * The icon stacks on top of a small spinner (drawn behind it) — the
 * spinner is the motion cue, the icon names the *kind* of activity.
 */
@Composable
private fun FetchingIndicator(size: Dp) {
  Box(contentAlignment = Alignment.Center) {
    CircularProgressIndicator(
      modifier = Modifier.size((size * 0.55f).coerceAtLeast(28.dp)),
      strokeWidth = 2.dp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Icon(
      imageVector = Icons.Outlined.CloudDownload,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size((size * 0.32f).coerceAtLeast(18.dp)),
    )
  }
}

@Composable
private fun Placeholder(size: Dp) {
  // Explicit `onSurfaceVariant` tint — the Icon default is
  // `LocalContentColor.current`, which is unstable across the chrome-
  // tint pipeline (the album palette nudges `surfaceVariant`, and a
  // default-black icon on a tinted-but-still-dark surface lands
  // invisible). Pinning to the M3 token guarantees the placeholder
  // always has the canonical-pair contrast against the tile bg.
  // Size at 60% of the tile so the music-note centres cleanly instead
  // of expanding edge-to-edge (the previous `size(size)` rendered the
  // icon at full tile width, which clipped against rounded corners).
  Icon(
    imageVector = Icons.Filled.MusicNote,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.size(size * 0.6f),
  )
}

/**
 * Build a content URI for the album art of [albumId]. Backed by the
 * legacy MediaStore `albumart` thumbnail provider.
 *
 * Visible for the Robolectric `CoverArtUriTest`.
 */
internal fun albumArtUri(albumId: Long): Uri =
  ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

/**
 * The MediaStore album-art base URI. This is the legacy path — the
 * post-API-29 documented replacement is
 * `ContentResolver.loadThumbnail(...)`, but the legacy URI is
 * supported through API 36 inclusive (verified on `emulator-5554`)
 * and integrates cleanly with Coil's `ContentResolver` fetcher.
 */
private val ALBUM_ART_BASE: Uri =
  Uri.parse("content://media/external/audio/albumart")
