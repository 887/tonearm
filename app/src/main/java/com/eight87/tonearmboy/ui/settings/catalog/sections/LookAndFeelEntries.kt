package com.eight87.tonearmboy.ui.settings.catalog.sections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Palette
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.ui.nav.SettingsLookAndFeel
import com.eight87.tonearmboy.ui.settings.catalog.Groups
import com.eight87.tonearmboy.ui.settings.catalog.RowKind
import com.eight87.tonearmboy.ui.settings.catalog.Section
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalog
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalogEntry

/** R.F.14 — entries on the Look and Feel sub-page. */
internal val LookAndFeelEntries: List<SettingsCatalogEntry> = listOf(
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_THEME,
    label = "Theme",
    subtitle = null,
    labelRes = R.string.settings_lookandfeel_theme_label,
    subtitleRes = null,
    keywords = listOf("dark", "light", "automatic", "system", "follow"),
    icon = Icons.Outlined.Palette,
    section = Section.LookAndFeel,
    group = Groups.Theme,
    kind = RowKind.Picker,
    destination = SettingsLookAndFeel,
    breadcrumb = listOf(SECTION_LOOK_AND_FEEL, "Theme", "Theme"),
  ),
  // D.20.4 — base-theme picker. Subsumes the legacy "Color scheme" +
  // "Black theme" pair into a single three-way decision; the album-art
  // tint toggle below sits on top.
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_BASE_THEME,
    label = "Base theme",
    subtitle = "Foundation colors. Album art tint sits on top.",
    labelRes = R.string.settings_lookandfeel_base_theme_label,
    subtitleRes = R.string.settings_lookandfeel_base_theme_subtitle,
    keywords = listOf(
      "dynamic", "material you", "brand", "palette",
      "amoled", "oled", "pure black", "static",
    ),
    icon = Icons.Outlined.ColorLens,
    section = Section.LookAndFeel,
    group = Groups.Theme,
    kind = RowKind.Picker,
    destination = SettingsLookAndFeel,
    breadcrumb = listOf(SECTION_LOOK_AND_FEEL, "Theme", "Base theme"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_ALBUM_ART_TINT,
    label = "Tint chrome by album art",
    subtitle = "Bias surfaces toward the playing track's dominant color.",
    labelRes = R.string.settings_lookandfeel_album_art_tint_label,
    subtitleRes = R.string.settings_lookandfeel_album_art_tint_subtitle,
    keywords = listOf("palette", "tint", "album", "cover", "art", "color"),
    icon = Icons.Outlined.Palette,
    section = Section.LookAndFeel,
    group = Groups.Theme,
    kind = RowKind.Toggle,
    destination = SettingsLookAndFeel,
    breadcrumb = listOf(SECTION_LOOK_AND_FEEL, "Theme", "Tint chrome by album art"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_ALBUM_ART_BACKGROUND,
    label = "Use album art as background",
    subtitle = "Blur the playing cover behind the chrome (Harmony-style).",
    labelRes = R.string.settings_lookandfeel_album_art_background_label,
    subtitleRes = R.string.settings_lookandfeel_album_art_background_subtitle,
    keywords = listOf("background", "blur", "glass", "harmony", "cover", "wallpaper"),
    icon = Icons.Outlined.Palette,
    section = Section.LookAndFeel,
    group = Groups.Theme,
    kind = RowKind.Toggle,
    destination = SettingsLookAndFeel,
    breadcrumb = listOf(SECTION_LOOK_AND_FEEL, "Theme", "Use album art as background"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_CUSTOM_CHROME_TINT,
    label = "Custom tint color",
    subtitle = "Pick a colour to override the album-art tint.",
    labelRes = R.string.settings_lookandfeel_custom_chrome_tint_label,
    subtitleRes = R.string.settings_lookandfeel_custom_chrome_tint_subtitle,
    keywords = listOf("custom", "tint", "color", "colour", "picker", "chrome", "manual"),
    icon = Icons.Outlined.Colorize,
    section = Section.LookAndFeel,
    group = Groups.Theme,
    kind = RowKind.Picker,
    destination = SettingsLookAndFeel,
    breadcrumb = listOf(SECTION_LOOK_AND_FEEL, "Theme", "Custom tint color"),
  ),
)
