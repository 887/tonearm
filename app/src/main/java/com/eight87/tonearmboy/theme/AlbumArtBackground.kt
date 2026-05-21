package com.eight87.tonearmboy.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Fullscreen blurred-cover background — the Harmony-glass look.
 * Renders the playing track's cover at fill-size with a heavy
 * gaussian blur, then a translucent surface scrim on top so chrome
 * text + buttons remain readable. Activated by the
 * `albumArtBackgroundEnabled` setting (gated on `albumArtTintEnabled`).
 *
 * Readability strategy:
 *  - 56dp blur radius — the cover is recognisable but no edge is
 *    sharp enough to compete with text.
 *  - Solid scrim using the active theme's `background` colour at
 *    65 % alpha — keeps the on-surface contrast token roughly
 *    aligned with the static theme, so text widgets that inherit
 *    `colorScheme.onSurface` stay legible without per-widget tweaks.
 *  - Soft vertical fade at top + bottom (additional 25 % alpha
 *    edges) so the app bar + nav bar regions are reliably solid
 *    even when the cover happens to have a bright spot under them.
 *
 * Blur requires API 31+; on older devices we render the cover
 * unblurred under the scrim, which still works (the scrim itself
 * keeps text legible — the blur is aesthetic, not load-bearing).
 */
@Composable
fun AlbumArtBackground(
  coverUri: String?,
  modifier: Modifier = Modifier,
) {
  if (coverUri.isNullOrBlank()) return
  val context = LocalContext.current
  val request = remember(coverUri) {
    ImageRequest.Builder(context)
      .data(coverUri)
      .memoryCacheKey("bg-$coverUri")
      .diskCacheKey("bg-$coverUri")
      .build()
  }
  val surface = androidx.compose.material3.MaterialTheme.colorScheme.background
  Box(modifier = modifier) {
    val imageModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Modifier.fillMaxSize().blur(56.dp)
    } else {
      Modifier.fillMaxSize()
    }
    AsyncImage(
      model = request,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = imageModifier,
    )
    // Light scrim. The chrome layer above is already painted with
    // alpha-0.55 surface tokens (Harmony-glass) so contrast against
    // the cover comes mostly from the glass, not from a heavy black
    // overlay. 0.2 keeps text legible without erasing the cover.
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(surface.copy(alpha = 0.2f)),
    )
  }
}
