package com.eight87.tonearmboy.ui.settings.catalog.sections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Sync
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.ui.nav.SettingsContent
import com.eight87.tonearmboy.ui.settings.catalog.Groups
import com.eight87.tonearmboy.ui.settings.catalog.RowKind
import com.eight87.tonearmboy.ui.settings.catalog.Section
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalog
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalogEntry

/**
 * R.F.14 — entries on the Content sub-page.
 *
 * Round 2 / Ask B — every cover-art row moved out of here into
 * [CoverArtEntries] / Section.CoverArt. What remains is the
 * tag-and-sorting metadata layer + the "Refresh album art" action
 * (kept here because the user discovers it next to "Refresh music"
 * in muscle-memory, not as a cover-art-provider concern).
 */
internal val ContentEntries: List<SettingsCatalogEntry> = listOf(
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_AUTOMATIC_RELOADING,
    label = "Automatic reloading",
    subtitle = "Watch for library changes and rescan automatically. Runs a foreground service.",
    labelRes = R.string.settings_content_automatic_reloading_label,
    subtitleRes = R.string.settings_content_automatic_reloading_subtitle,
    keywords = listOf("watch", "reload", "background", "observer", "rescan"),
    icon = Icons.Outlined.Sync,
    section = Section.Content,
    group = Groups.Music,
    kind = RowKind.Toggle,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Music", "Automatic reloading"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_MULTI_VALUE_SEPARATORS,
    label = "Multi-value separators",
    subtitle = null,
    labelRes = R.string.settings_content_multi_value_separators_label,
    subtitleRes = null,
    keywords = listOf("artist", "split", "feat", "comma", "semicolon", "ampersand", "slash"),
    icon = Icons.Outlined.MoreHoriz,
    section = Section.Content,
    group = Groups.Music,
    kind = RowKind.Picker,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Music", "Multi-value separators"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_INTELLIGENT_SORTING,
    label = "Intelligent sorting",
    subtitle = "Ignore leading articles (English, French, German, Spanish, Italian, Dutch) when sorting.",
    labelRes = R.string.settings_content_intelligent_sorting_label,
    subtitleRes = R.string.settings_content_intelligent_sorting_subtitle,
    keywords = listOf(
      "sort", "alphabetical", "the", "articles",
      "le", "la", "der", "die", "el", "il", "de",
    ),
    icon = Icons.Outlined.SortByAlpha,
    section = Section.Content,
    group = Groups.Music,
    kind = RowKind.Toggle,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Music", "Intelligent sorting"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_HIDE_COLLABORATORS,
    label = "Hide collaborators",
    subtitle = "Show only the primary album artist; collapse featured-artist credits.",
    labelRes = R.string.settings_content_hide_collaborators_label,
    subtitleRes = R.string.settings_content_hide_collaborators_subtitle,
    keywords = listOf("artist", "album artist", "feat"),
    icon = Icons.Outlined.PersonOff,
    section = Section.Content,
    group = Groups.Music,
    kind = RowKind.Toggle,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Music", "Hide collaborators"),
  ),
  // Refresh album art — kept on Content. The action drops Coil's cache;
  // it isn't tied to providers / kill switch state, and users discover
  // it next to "Refresh music" / "Rescan music".
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_REFRESH_ALBUM_ART,
    label = "Refresh album art",
    subtitle = "Reload covers from disk. Use this after replacing cover files.",
    labelRes = R.string.settings_root_refresh_album_art_label,
    subtitleRes = R.string.settings_root_refresh_album_art_subtitle,
    keywords = listOf("cover", "art", "reload", "refresh", "album"),
    icon = Icons.Outlined.Refresh,
    section = Section.Content,
    group = Groups.Music,
    kind = RowKind.Action,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Music", "Refresh album art"),
  ),
)
