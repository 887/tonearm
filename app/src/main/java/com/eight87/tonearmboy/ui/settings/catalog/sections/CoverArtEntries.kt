package com.eight87.tonearmboy.ui.settings.catalog.sections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Public
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.ui.nav.SettingsCoverArt
import com.eight87.tonearmboy.ui.settings.catalog.Groups
import com.eight87.tonearmboy.ui.settings.catalog.RowKind
import com.eight87.tonearmboy.ui.settings.catalog.Section
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalog
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalogEntry

/**
 * Round 2 / Ask B — entries on the new top-level Cover art sub-page.
 *
 * Lifted out of [ContentEntries] so cover-art settings get their own
 * section between Behaviour and Library on the Settings root. Group
 * stays as `AlbumArtSources` for the privacy / providers / fill-now
 * trio, plus an Images group for the display-mode rows.
 */
internal val CoverArtEntries: List<SettingsCatalogEntry> = listOf(
  // Privacy kill switch first — flipping this off is the gate that
  // unlocks every other web-touching row below.
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_COVER_ART_DISABLED,
    label = "Turn off online cover-art lookups",
    subtitle = "When on, the app makes no web requests for cover art. Your provider list below is preserved.",
    labelRes = R.string.settings_content_cover_art_disabled_label,
    subtitleRes = R.string.settings_content_cover_art_disabled_subtitle,
    keywords = listOf("cover", "art", "privacy", "offline", "disable", "no", "network"),
    icon = Icons.Outlined.Public,
    section = Section.CoverArt,
    group = Groups.AlbumArtSources,
    kind = RowKind.Toggle,
    destination = SettingsCoverArt,
    breadcrumb = listOf(SECTION_COVER_ART, "Turn off online cover-art lookups"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_COVER_ART_PROVIDERS,
    label = "Cover art providers",
    subtitle = "Drag to reorder; toggle to enable. Top of the list is tried first.",
    labelRes = R.string.settings_content_cover_art_providers_label,
    subtitleRes = R.string.settings_content_cover_art_providers_subtitle,
    keywords = listOf("cover", "art", "providers", "youtube", "musicbrainz", "itunes", "apple", "newpipe", "piped", "order"),
    icon = Icons.Outlined.Public,
    section = Section.CoverArt,
    group = Groups.AlbumArtSources,
    kind = RowKind.Picker,
    destination = SettingsCoverArt,
    breadcrumb = listOf(SECTION_COVER_ART, "Cover art providers"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_FILL_MISSING_COVERS,
    label = "Fill in missing covers now",
    subtitle = "Look up cover art for every song in your library that doesn't have one yet.",
    labelRes = R.string.settings_content_fill_missing_covers_label,
    subtitleRes = R.string.settings_content_fill_missing_covers_subtitle,
    keywords = listOf("cover", "fill", "now", "fetch", "progress", "log"),
    icon = Icons.Outlined.Download,
    // Round 2 / Ask A — this row used to fire-and-forget enqueue the
    // worker. It now navigates to a live progress sub-page where the
    // user can start the run and watch every album get attempted.
    section = Section.CoverArt,
    group = Groups.AlbumArtSources,
    kind = RowKind.Picker,
    destination = SettingsCoverArt,
    breadcrumb = listOf(SECTION_COVER_ART, "Fill in missing covers now"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_AUTO_DISCOVER_ALBUM_ART,
    label = "Auto-discover missing album art",
    subtitle = "Schedule a one-shot bulk pass for albums missing local art. Uses the cover-art service picked below; does nothing while the service is set to None.",
    labelRes = R.string.settings_content_auto_discover_album_art_label,
    subtitleRes = R.string.settings_content_auto_discover_album_art_subtitle,
    keywords = listOf("cover", "art", "fetch", "download", "bulk"),
    icon = Icons.Outlined.CloudDownload,
    section = Section.CoverArt,
    group = Groups.AlbumArtSources,
    kind = RowKind.Toggle,
    destination = SettingsCoverArt,
    breadcrumb = listOf(SECTION_COVER_ART, "Auto-discover missing album art"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_SCAN_FOLDERS_FOR_COVER_ART,
    label = "Scan folders for cover art",
    subtitle = "Pick up cover.jpg / folder.jpg / albumart.jpg files next to album folders during library scan. FilePicker mode only.",
    labelRes = R.string.settings_content_scan_folders_for_cover_art_label,
    subtitleRes = R.string.settings_content_scan_folders_for_cover_art_subtitle,
    keywords = listOf("cover", "folder", "scan", "art", "embed", "local"),
    icon = Icons.Outlined.FolderOpen,
    section = Section.CoverArt,
    group = Groups.AlbumArtSources,
    kind = RowKind.Toggle,
    destination = SettingsCoverArt,
    breadcrumb = listOf(SECTION_COVER_ART, "Scan folders for cover art"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_ALBUM_COVERS,
    label = "Album covers",
    subtitle = "Balanced (default) decodes covers at the cell's display size — fastest. Always load decodes at full resolution (slower, sharper on high-DPI). Never load skips covers entirely.",
    labelRes = R.string.settings_content_album_covers_label,
    subtitleRes = null,
    keywords = listOf("art", "image", "loading", "balanced", "coil"),
    icon = Icons.Outlined.Photo,
    section = Section.CoverArt,
    group = Groups.Images,
    kind = RowKind.Picker,
    destination = SettingsCoverArt,
    breadcrumb = listOf(SECTION_COVER_ART, "Album covers"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_FORCE_SQUARE_COVERS,
    label = "Force square album covers",
    subtitle = "Render covers as squares instead of rounded rectangles.",
    labelRes = R.string.settings_content_force_square_covers_label,
    subtitleRes = R.string.settings_content_force_square_covers_subtitle,
    keywords = listOf("rounded", "square", "art"),
    icon = Icons.Outlined.CropSquare,
    section = Section.CoverArt,
    group = Groups.Images,
    kind = RowKind.Toggle,
    destination = SettingsCoverArt,
    breadcrumb = listOf(SECTION_COVER_ART, "Force square album covers"),
  ),
)
