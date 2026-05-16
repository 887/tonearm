package com.eight87.tonearmboy.ui.settings.catalog.sections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.ui.nav.SettingsContent
import com.eight87.tonearmboy.ui.settings.catalog.Groups
import com.eight87.tonearmboy.ui.settings.catalog.RowKind
import com.eight87.tonearmboy.ui.settings.catalog.Section
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalog
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalogEntry

/** R.F.14 — entries on the Content sub-page. */
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
  // album-art Phase E — "Album art sources" group, between the
  // Music group and the Images group. Each provider gets its own
  // toggle; embedded MediaStore art is implicit (always on) so
  // there's no row for it. Order: folder scan first (closest to
  // the user's library), MusicBrainz second (network).
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_SCAN_FOLDERS_FOR_COVER_ART,
    label = "Scan folders for cover art",
    subtitle = "Pick up cover.jpg / folder.jpg / albumart.jpg files next to album folders during library scan. FilePicker mode only.",
    labelRes = R.string.settings_content_scan_folders_for_cover_art_label,
    subtitleRes = R.string.settings_content_scan_folders_for_cover_art_subtitle,
    keywords = listOf("cover", "folder", "scan", "art", "embed", "local"),
    icon = Icons.Outlined.FolderOpen,
    section = Section.Content,
    group = Groups.AlbumArtSources,
    kind = RowKind.Toggle,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Album art sources", "Scan folders for cover art"),
  ),
  // album-art Phase D — re-introduced as a real toggle. Backed by
  // AlbumArtBulkWorker. The cover-art *service* picker (below) is
  // the privacy gate; this toggle just controls whether the bulk
  // worker is scheduled. Default OFF.
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_AUTO_DISCOVER_ALBUM_ART,
    label = "Auto-discover missing album art",
    subtitle = "Schedule a one-shot bulk pass for albums missing local art. Uses the cover-art service picked below; does nothing while the service is set to None.",
    labelRes = R.string.settings_content_auto_discover_album_art_label,
    subtitleRes = R.string.settings_content_auto_discover_album_art_subtitle,
    keywords = listOf("cover", "art", "fetch", "download", "bulk"),
    icon = Icons.Outlined.CloudDownload,
    section = Section.Content,
    group = Groups.AlbumArtSources,
    kind = RowKind.Toggle,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Album art sources", "Auto-discover missing album art"),
  ),
  // Cover-art Phase D — privacy kill switch. ON = chain bypassed
  // entirely (zero web requests); OFF (default) = active providers in
  // the priority list below are walked.
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_COVER_ART_DISABLED,
    label = "Turn off online cover-art lookups",
    subtitle = "When on, the app makes no web requests for cover art. Your provider list below is preserved.",
    labelRes = R.string.settings_content_cover_art_disabled_label,
    subtitleRes = R.string.settings_content_cover_art_disabled_subtitle,
    keywords = listOf("cover", "art", "privacy", "offline", "disable", "no", "network"),
    icon = Icons.Outlined.Public,
    section = Section.Content,
    group = Groups.AlbumArtSources,
    kind = RowKind.Toggle,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Album art sources", "Turn off online cover-art lookups"),
  ),
  // Cover-art Phase D — opens the reorderable provider list sub-page.
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_COVER_ART_PROVIDERS,
    label = "Cover art providers",
    subtitle = "Drag to reorder; toggle to enable. Top of the list is tried first.",
    labelRes = R.string.settings_content_cover_art_providers_label,
    subtitleRes = R.string.settings_content_cover_art_providers_subtitle,
    keywords = listOf("cover", "art", "providers", "youtube", "musicbrainz", "itunes", "apple", "newpipe", "piped", "order"),
    icon = Icons.Outlined.Public,
    section = Section.Content,
    group = Groups.AlbumArtSources,
    kind = RowKind.Picker,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Album art sources", "Cover art providers"),
  ),
  // MusicBrainz match-score slider. Only meaningful when service is
  // MusicBrainz; the row stays visible regardless so the user can
  // see and tune their threshold without first switching service.
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_COVER_ART_MATCH_SCORE,
    label = "MusicBrainz match threshold",
    subtitle = "How picky to be about MusicBrainz hits. 100 = perfect match only; 50 = accept fuzzy matches. Default 70. Only used when the service above is MusicBrainz.",
    labelRes = R.string.settings_content_cover_art_match_score_label,
    subtitleRes = R.string.settings_content_cover_art_match_score_subtitle,
    keywords = listOf("cover", "art", "musicbrainz", "score", "threshold", "match", "fuzzy"),
    icon = Icons.Outlined.Tune,
    section = Section.Content,
    group = Groups.AlbumArtSources,
    kind = RowKind.Picker,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Album art sources", "MusicBrainz match threshold"),
  ),
  // album-art — one-shot "fill missing covers now" action. The
  // Auto-discover toggle persists a preference; this action is
  // explicit "do it once" — same backend worker, but enqueued
  // regardless of the toggle's state. Useful when the user adds
  // new music after a previous bulk pass already finished.
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_FILL_MISSING_COVERS,
    label = "Fill in missing covers now",
    subtitle = "Run a one-shot MusicBrainz lookup for every album currently missing art.",
    labelRes = R.string.settings_content_fill_missing_covers_label,
    subtitleRes = R.string.settings_content_fill_missing_covers_subtitle,
    keywords = listOf("cover", "fill", "now", "fetch", "musicbrainz"),
    icon = Icons.Outlined.Download,
    section = Section.Content,
    group = Groups.AlbumArtSources,
    kind = RowKind.Action,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Album art sources", "Fill in missing covers now"),
  ),
  // album-art — Refresh album art moved here from Settings root.
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_REFRESH_ALBUM_ART,
    label = "Refresh album art",
    subtitle = "Reload covers from disk. Use this after replacing cover files.",
    labelRes = R.string.settings_root_refresh_album_art_label,
    subtitleRes = R.string.settings_root_refresh_album_art_subtitle,
    keywords = listOf("cover", "art", "reload", "refresh", "album"),
    icon = Icons.Outlined.Refresh,
    section = Section.Content,
    group = Groups.AlbumArtSources,
    kind = RowKind.Action,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Album art sources", "Refresh album art"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_ALBUM_COVERS,
    label = "Album covers",
    subtitle = "Balanced (default) decodes covers at the cell's display size — fastest. Always load decodes at full resolution (slower, sharper on high-DPI). Never load skips covers entirely.",
    labelRes = R.string.settings_content_album_covers_label,
    subtitleRes = null,
    keywords = listOf("art", "image", "loading", "balanced", "coil"),
    icon = Icons.Outlined.Photo,
    section = Section.Content,
    group = Groups.Images,
    kind = RowKind.Picker,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Images", "Album covers"),
  ),
  SettingsCatalogEntry(
    id = SettingsCatalog.ID_FORCE_SQUARE_COVERS,
    label = "Force square album covers",
    subtitle = "Render covers as squares instead of rounded rectangles.",
    labelRes = R.string.settings_content_force_square_covers_label,
    subtitleRes = R.string.settings_content_force_square_covers_subtitle,
    keywords = listOf("rounded", "square", "art"),
    icon = Icons.Outlined.CropSquare,
    section = Section.Content,
    group = Groups.Images,
    kind = RowKind.Toggle,
    destination = SettingsContent,
    breadcrumb = listOf(SECTION_CONTENT, "Images", "Force square album covers"),
  ),
)
