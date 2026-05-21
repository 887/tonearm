package com.eight87.tonearmboy.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Fullscreen blurred-cover background — the Harmony-glass look.
 * Renders the playing track's cover at fill-size with a heavy
 * gaussian blur, then a light scrim so the chrome layer above
 * (alpha-0.5 surface tokens) stays readable against any cover.
 *
 * Blur via a custom [android.graphics.RenderEffect] with
 * [android.graphics.Shader.TileMode.CLAMP] — sampling past the edge
 * reuses the nearest cover pixel instead of going to transparent /
 * black. `Modifier.blur()` uses `TileMode.DECAL` under the hood and
 * produced visible black bars at the top + bottom of the screen
 * because the cover's portrait crop didn't extend past the blur
 * radius in those dimensions.
 *
 * Requires API 31+; on older devices we render the cover unblurred
 * under the scrim, which still works (the scrim itself keeps text
 * legible — the blur is aesthetic, not load-bearing).
 */
@Composable
fun AlbumArtBackground(
  coverUri: String?,
  modifier: Modifier = Modifier,
) {
  if (coverUri.isNullOrBlank()) return
  val context = LocalContext.current
  val density = LocalDensity.current
  val request = remember(coverUri) {
    ImageRequest.Builder(context)
      .data(coverUri)
      .memoryCacheKey("bg-$coverUri")
      .diskCacheKey("bg-$coverUri")
      .build()
  }
  val blurRadiusPx = with(density) { 56.dp.toPx() }
  val composeBlurEffect = remember(blurRadiusPx) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      android.graphics.RenderEffect.createBlurEffect(
        blurRadiusPx,
        blurRadiusPx,
        android.graphics.Shader.TileMode.CLAMP,
      ).asComposeRenderEffect()
    } else null
  }
  val surface = androidx.compose.material3.MaterialTheme.colorScheme.background
  Box(modifier = modifier) {
    AsyncImage(
      model = request,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          if (composeBlurEffect != null) renderEffect = composeBlurEffect
        },
    )
    // Light scrim. The chrome above paints with alpha-0.5 surface
    // tokens, so contrast against the cover comes mostly from the
    // glass — keep this scrim minimal so the cover stays vivid.
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(surface.copy(alpha = 0.2f)),
    )
  }
}
