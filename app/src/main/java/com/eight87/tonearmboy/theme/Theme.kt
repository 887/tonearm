@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.eight87.tonearmboy.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.eight87.tonearmboy.ui.settings.BaseTheme

/**
 * Three-mode app theme backed by Material 3 with dynamic color (Material You)
 * on Android 12+, plus an album-art tint layer on top.
 *
 * The base scheme is determined by [baseTheme]:
 *
 *  - [BaseTheme.DefaultAndroid] — Material You / dynamic colour on
 *    API 31+, falls back to the brand palette on older devices.
 *  - [BaseTheme.DefaultColors] — the static brand palette regardless
 *    of API.
 *  - [BaseTheme.PureBlack] — same primary colours as `DefaultAndroid`
 *    but with `surface` / `background` collapsed to pure black for
 *    AMOLED-friendly displays.
 *
 * When [albumArtTintEnabled] is true and `LocalAlbumPalette` carries a
 * non-null `surfaceTint`, the chrome `surface` / `surfaceVariant` /
 * `background` shift toward that tint, animated via
 * [animateColorAsState] so transitions are smooth. When the tint is
 * null (no track / extraction failed) the chrome stays on the base.
 */
// m3-expressive A.4 / B.1 — fall through to the expressive seed
// schemes. They produce brighter container pairs (`primaryContainer` /
// `secondaryContainer` / `tertiaryContainer`) and the wider
// surface-tier ladder (`surfaceContainerLow…High`) the M3E look needs.
// `expressiveDarkColorScheme()` doesn't exist yet — only the light
// variant ships in 1.4.0 — so we pair `expressiveLightColorScheme()`
// for light mode with `darkColorScheme()` plus our brand seeds for
// dark mode. Both still produce the full surface-tier ladder.
private val DarkColorScheme = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme = expressiveLightColorScheme()

@Composable
fun TonearmboyTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  baseTheme: BaseTheme = BaseTheme.Default,
  albumArtTintEnabled: Boolean = true,
  // Custom-chrome-tint override. When non-null, this colour replaces
  // the album-art-derived palette tint regardless of
  // [albumArtTintEnabled] — picking a colour is the explicit "I want
  // this colour" gesture. When null, we fall through to the album
  // palette path.
  customChromeTint: Color? = null,
  // When true, every theme surface token paints with reduced alpha so
  // the fullscreen blurred-cover layer beneath the activity Surface
  // shows through every Material widget (Harmony-style glass). Pair
  // with the `AlbumArtBackground` composable + a transparent root
  // Surface in the activity; widgets read `colorScheme.surface*` and
  // become translucent automatically.
  albumArtBackgroundActive: Boolean = false,
  content: @Composable () -> Unit,
) {
  val baseScheme = resolveBaseScheme(darkTheme, baseTheme)

  val palette = LocalAlbumPalette.current
  val tint = customChromeTint
    ?: if (albumArtTintEnabled) palette.surfaceTint else null

  // Direct `blendSurface` — pure function call, no per-color State
  // allocation. The earlier implementation wrapped each surface in
  // `animateColorAsState(..., tween(300))` to crossfade chrome between
  // tracks; that allocated 9 State<Color>s + 9 LaunchedEffects on
  // every composition (every cold start, even when no album is
  // playing and the animation target equals the base value), which
  // was material for first-frame composition cost. Compose still
  // recomposes Theme on tint change because `tint` is read above and
  // any change re-invokes the function — colours just snap rather
  // than crossfade. Acceptable trade for the startup win.
  val tintedScheme = if (tint == null) baseScheme else baseScheme.copy(
    surface = blendSurface(baseScheme.surface, tint),
    surfaceVariant = blendSurface(baseScheme.surfaceVariant, tint),
    background = blendSurface(baseScheme.background, tint),
    surfaceContainerLowest = blendSurface(baseScheme.surfaceContainerLowest, tint),
    surfaceContainerLow = blendSurface(baseScheme.surfaceContainerLow, tint),
    surfaceContainer = blendSurface(baseScheme.surfaceContainer, tint),
    surfaceContainerHigh = blendSurface(baseScheme.surfaceContainerHigh, tint),
    surfaceContainerHighest = blendSurface(baseScheme.surfaceContainerHighest, tint),
    secondaryContainer = blendSurface(baseScheme.secondaryContainer, tint),
  )

  // Harmony-glass: when the album-art background layer is active,
  // override **only** `colorScheme.background` to fully transparent.
  // Every Material widget that paints its own container (Card,
  // TopAppBar, Surface, Sheet, MiniPlayer, transport-row Surface)
  // reads `colorScheme.surface*` — those stay solid (and tinted by
  // the album palette via the blend above). Scaffolds default
  // `containerColor` to `colorScheme.background`, so flipping that
  // single token to Color.Transparent makes the Scaffold's "void"
  // band reveal the blurred cover painted behind the activity root
  // Surface, while every actual chrome widget keeps its opaque
  // tinted container. That's the Harmony pattern: cover as wallpaper,
  // chrome on top as opaque panels.
  val colorScheme = if (!albumArtBackgroundActive) tintedScheme else {
    tintedScheme.copy(background = Color.Transparent)
  }

  CompositionLocalProvider(
    // Re-publish the palette local from the receiving site so child
    // composables can consult it directly when they need to read the
    // tint themselves (e.g. a Preview that wants the same bias).
    LocalAlbumPalette provides palette,
  ) {
    // m3-expressive A.5 — `MaterialExpressiveTheme` pulls in the new
    // motion / typography / shape defaults (rounded extra-large group
    // shapes, faster spring-based motion). It still resolves
    // `MaterialTheme.colorScheme` against whatever scheme we hand in,
    // so the per-track album tint blending above continues to work.
    MaterialExpressiveTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}

/**
 * Resolve the foundation [ColorScheme] for the active [BaseTheme],
 * before any album-palette tint is applied.
 *
 * Pulled out as a top-level composable so a Robolectric test can
 * exercise the three-way picker logic without instantiating the full
 * theme + palette plumbing.
 */
@Composable
internal fun resolveBaseScheme(darkTheme: Boolean, baseTheme: BaseTheme): ColorScheme {
  val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
  return when (baseTheme) {
    BaseTheme.DefaultAndroid -> {
      if (dynamicAvailable) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      } else {
        if (darkTheme) DarkColorScheme else LightColorScheme
      }
    }
    BaseTheme.DefaultColors -> if (darkTheme) DarkColorScheme else LightColorScheme
    BaseTheme.PureBlack -> {
      val foundation = if (dynamicAvailable) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      } else {
        if (darkTheme) DarkColorScheme else LightColorScheme
      }
      foundation.copy(background = Color.Black, surface = Color.Black)
    }
    is BaseTheme.Custom -> deriveCustomScheme(baseTheme.seedRgb, darkTheme)
  }
}

/**
 * D.25.1 — derive a Material 3 [ColorScheme] from a 24-bit RGB seed.
 *
 * Strategy: build primary / secondary / tertiary tonal anchors by
 * shifting the seed's hue (secondary = +30°, tertiary = +60°) and
 * lightness, then plug them into the canonical
 * `lightColorScheme` / `darkColorScheme` factories. This sidesteps
 * Material 3's `dynamicColorScheme(seed, isDark)` (added in 1.4) so
 * the build works regardless of the active Material 3 version.
 *
 * Pure helper for unit-testability — no Compose runtime required.
 */
internal fun deriveCustomScheme(seedRgb: Long, darkTheme: Boolean): ColorScheme {
  val primary = colorFromRgbLong(seedRgb)
  val (h, s, _) = rgbToHslTriple(primary)
  val secondary = hslColor(((h + 30f) % 360f), (s * 0.7f).coerceIn(0f, 1f), if (darkTheme) 0.7f else 0.45f)
  val tertiary = hslColor(((h + 60f) % 360f), (s * 0.6f).coerceIn(0f, 1f), if (darkTheme) 0.7f else 0.5f)
  val primaryDark = hslColor(h, s, if (darkTheme) 0.7f else 0.4f)
  val onPrimary = if (luminance(primaryDark) > 0.5f) Color.Black else Color.White
  return if (darkTheme) {
    darkColorScheme(
      primary = primaryDark,
      secondary = secondary,
      tertiary = tertiary,
      onPrimary = onPrimary,
    )
  } else {
    lightColorScheme(
      primary = primaryDark,
      secondary = secondary,
      tertiary = tertiary,
      onPrimary = onPrimary,
    )
  }
}

private fun colorFromRgbLong(rgb: Long): Color {
  val r = ((rgb shr 16) and 0xFFL).toInt()
  val g = ((rgb shr 8) and 0xFFL).toInt()
  val b = (rgb and 0xFFL).toInt()
  return Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = 1f)
}

/** Returns (hue 0..360, saturation 0..1, lightness 0..1). */
internal fun rgbToHslTriple(c: Color): Triple<Float, Float, Float> {
  val r = c.red; val g = c.green; val b = c.blue
  val max = maxOf(r, g, b); val min = minOf(r, g, b)
  val l = (max + min) / 2f
  val delta = max - min
  if (delta == 0f) return Triple(0f, 0f, l)
  val s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
  val h = when (max) {
    r -> 60f * (((g - b) / delta) % 6f)
    g -> 60f * (((b - r) / delta) + 2f)
    else -> 60f * (((r - g) / delta) + 4f)
  }.let { if (it < 0f) it + 360f else it }
  return Triple(h, s, l)
}

internal fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
  val h = ((hue % 360f) + 360f) % 360f
  val s = saturation.coerceIn(0f, 1f)
  val l = lightness.coerceIn(0f, 1f)
  val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
  val hp = h / 60f
  val x = c * (1f - kotlin.math.abs((hp % 2f) - 1f))
  val (r1, g1, b1) = when {
    hp < 1f -> Triple(c, x, 0f)
    hp < 2f -> Triple(x, c, 0f)
    hp < 3f -> Triple(0f, c, x)
    hp < 4f -> Triple(0f, x, c)
    hp < 5f -> Triple(x, 0f, c)
    else -> Triple(c, 0f, x)
  }
  val m = l - c / 2f
  return Color(red = (r1 + m).coerceIn(0f, 1f), green = (g1 + m).coerceIn(0f, 1f), blue = (b1 + m).coerceIn(0f, 1f), alpha = 1f)
}

internal fun luminance(c: Color): Float {
  // Relative luminance per WCAG-ish; cheap enough for an on-color decision.
  return 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
}

/**
 * Blend the base surface colour toward the album-palette [tint]. We
 * keep the bias modest (40 % of the way to the tint) so chrome
 * remains chrome — the user is meant to read it as "tinted by the
 * cover", not "becomes the cover". Returning [base] unchanged when
 * the tint is null preserves the static-theme path bit-for-bit.
 *
 * Pure helper for unit-testability — no Compose runtime required.
 */
internal fun blendSurface(base: Color, tint: Color?, fraction: Float = 0.4f): Color {
  if (tint == null) return base
  val f = fraction.coerceIn(0f, 1f)
  return Color(
    red = base.red * (1f - f) + tint.red * f,
    green = base.green * (1f - f) + tint.green * f,
    blue = base.blue * (1f - f) + tint.blue * f,
    alpha = base.alpha,
  )
}
