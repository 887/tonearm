package com.eight87.tonearmboy.ui.settings.catalog

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.ui.settings.catalog.sections.AudioEntries
import com.eight87.tonearmboy.ui.settings.catalog.sections.ContentEntries
import com.eight87.tonearmboy.ui.settings.catalog.sections.CoverArtEntries
import com.eight87.tonearmboy.ui.settings.catalog.sections.LookAndFeelEntries
import com.eight87.tonearmboy.ui.settings.catalog.sections.PersonalizeEntries
import com.eight87.tonearmboy.ui.settings.catalog.sections.RootEntries

/**
 * Where this entry sits in the settings hierarchy. The root settings
 * screen is rendered from entries whose [SettingsCatalogEntry.section]
 * is [Section.Root]; each sub-page is rendered from entries with the
 * matching section value.
 */
enum class Section { Root, LookAndFeel, Personalize, CoverArt, Content, Audio }

/**
 * R.F.15 — grouping bucket inside a section. All entries with the same
 * (section, group) pair render inside one `SettingsCard`. Order of
 * entries inside the card follows the order they appear in the catalog.
 * (Settings-F8.)
 *
 * Replaced the pre-R.F.15 `enum class Group` whose `PersonalizeBehaviour`
 * variant existed solely to dodge a name collision with the root-page
 * `Behaviour`. With `GroupRef` carrying its own label inline, two
 * sections can both have a "Behaviour" group without enum-name games.
 */
data class GroupRef(@StringRes val labelRes: Int)

/** R.F.15 — pre-built group refs reused by the catalog entries. */
internal object Groups {
  val Appearance = GroupRef(R.string.settings_group_appearance)
  val Behaviour = GroupRef(R.string.settings_group_behaviour)
  val Library = GroupRef(R.string.settings_group_library)
  val About = GroupRef(R.string.settings_group_about)
  val Theme = GroupRef(R.string.settings_group_theme)
  val Display = GroupRef(R.string.settings_group_display)
  val PersonalizeBehaviour = GroupRef(R.string.settings_group_behaviour)
  val Music = GroupRef(R.string.settings_group_music)
  val Images = GroupRef(R.string.settings_group_images)
  val Playback = GroupRef(R.string.settings_group_playback)
  val VolumeNormalization = GroupRef(R.string.settings_group_volume_normalization)
  /** album-art Phase E — gathers the Phase B + D toggles into one
   *  "Album art sources" group on the Content sub-page. */
  val AlbumArtSources = GroupRef(R.string.settings_group_album_art_sources)
}

/**
 * What kind of UI affordance the row exposes. The page renderer
 * switches on this to decide whether the row needs a Switch, a picker
 * dialog, a snackbar stub, or a plain navigation tap.
 *
 * The stable [SettingsCatalogEntry.id] is what the page binds to a
 * concrete handler; this enum just describes the trailing affordance.
 */
enum class RowKind {
  /** Tapping navigates to another destination. */
  Navigate,
  /** Boolean toggle wired to the repository. */
  Toggle,
  /** Picker (radio dialog) wired to the repository. */
  Picker,
  /** Leaf action with a confirmation or immediate effect. */
  Action,
  /**
   * D.17.3 — tapping opens a modal `Dialog` rendered above the current
   * page; no navigation push. Used for Music sources so the user can
   * adjust the source set + mode without leaving the Settings root
   * surface.
   */
  OpenDialog,
  /** Not yet wired — taps trigger a "Coming in v1.1" snackbar. */
  Stub,
}

/**
 * One settings entry. The catalog is the single source of truth: every
 * row visible in any settings sub-page comes from this list, and the
 * global search filters this list. There is no parallel "screen
 * definition" — the screens render by filtering the catalog.
 */
data class SettingsCatalogEntry(
  val id: String,
  /**
   * Canonical English label. Kept inline alongside [labelRes] because:
   *
   *  - the JVM-only `SettingsCatalogTest` exercises catalog shape +
   *    breadcrumb composition without a Robolectric / Context (the
   *    last breadcrumb segment must equal the label, etc), and
   *  - [SettingsCatalog.search] runs a substring match against label
   *    + subtitle + keywords, again without a Context handle.
   *
   * The runtime UI (sub-page rows, Settings root, search overlay)
   * resolves [labelRes] / [subtitleRes] via Compose `stringResource`
   * so translations land. T.A.7's audit pass will tighten this up
   * once the catalog can take a Context for search resolution.
   */
  val label: String,
  /** Canonical English subtitle, for the same reason as [label]. */
  val subtitle: String? = null,
  /** Translated string resource for the row label. */
  @StringRes val labelRes: Int,
  /** Translated string resource for the row subtitle, when present. */
  @StringRes val subtitleRes: Int? = null,
  val keywords: List<String> = emptyList(),
  val icon: ImageVector,
  val section: Section,
  val group: GroupRef,
  val kind: RowKind,
  val destination: NavKey,
  val breadcrumb: List<String>,
)

/**
 * Single source of truth for every settings row. Adding a new setting
 * is one entry in the appropriate `sections/<Section>Entries.kt` file
 * plus a binding in the corresponding sub-page renderer. The
 * Robolectric `SettingsCatalogTest` proves no orphans (every wired UI
 * row has a catalog entry) and no unreachable entries (every catalog
 * entry is rendered by some sub-page).
 *
 * R.F.14 — entries themselves live in the `sections/` package, one
 * file per [Section]. This file is the type-definitions + ID-constants
 * + aggregator + lookup helpers.
 */
object SettingsCatalog {

  // Stable IDs. Tests reference these; do not rename without updating
  // both the bindings in `SettingsPagesRender.kt` and the test file.
  const val ID_APPEARANCE_LOOK_AND_FEEL = "appearance.look_and_feel"
  const val ID_APPEARANCE_PERSONALIZE = "appearance.personalize"
  const val ID_BEHAVIOUR_CONTENT = "behaviour.content"
  const val ID_BEHAVIOUR_AUDIO = "behaviour.audio"
  /** Round 2 / Ask B — top-level Cover art section, between Behaviour and Library. */
  const val ID_BEHAVIOUR_COVER_ART = "behaviour.cover_art"
  const val ID_LIBRARY_MUSIC_SOURCES = "library.music_sources"
  const val ID_LIBRARY_REFRESH = "library.refresh"
  const val ID_LIBRARY_RESCAN = "library.rescan"
  const val ID_ABOUT = "about"

  const val ID_THEME = "look_and_feel.theme"
  const val ID_BASE_THEME = "look_and_feel.base_theme"
  const val ID_ALBUM_ART_TINT = "look_and_feel.album_art_tint"
  const val ID_CUSTOM_CHROME_TINT = "look_and_feel.custom_chrome_tint"

  const val ID_LIBRARY_TABS = "personalize.library_tabs"
  const val ID_CUSTOM_PLAYBACK_BAR_ACTION = "personalize.custom_playback_bar_action"
  const val ID_CUSTOM_NOTIFICATION_ACTION = "personalize.custom_notification_action"
  const val ID_PLAY_FROM_LIBRARY = "personalize.play_from_library"
  const val ID_PLAY_FROM_ITEM_DETAILS = "personalize.play_from_item_details"

  const val ID_AUTOMATIC_RELOADING = "content.automatic_reloading"
  const val ID_MULTI_VALUE_SEPARATORS = "content.multi_value_separators"
  const val ID_INTELLIGENT_SORTING = "content.intelligent_sorting"
  const val ID_HIDE_COLLABORATORS = "content.hide_collaborators"
  const val ID_ALBUM_COVERS = "content.album_covers"
  const val ID_AUTO_DISCOVER_ALBUM_ART = "content.auto_discover_album_art"
  const val ID_COVER_ART_SERVICE = "content.cover_art_service"
  const val ID_COVER_ART_MATCH_SCORE = "content.cover_art_match_score"
  // Cover-art Phase D — replaces ID_COVER_ART_SERVICE.
  const val ID_COVER_ART_PROVIDERS = "content.cover_art_providers"
  // Cover-art Phase D — privacy kill switch above the providers row.
  const val ID_COVER_ART_DISABLED = "content.cover_art_disabled"
  const val ID_SCAN_FOLDERS_FOR_COVER_ART = "content.scan_folders_for_cover_art"
  const val ID_FILL_MISSING_COVERS = "content.fill_missing_covers"
  const val ID_REFRESH_ALBUM_ART = "content.refresh_album_art"
  const val ID_FORCE_SQUARE_COVERS = "content.force_square_covers"

  const val ID_PAUSE_ON_REPEAT = "audio.pause_on_repeat"
  const val ID_REPLAYGAIN_STRATEGY = "audio.replaygain_strategy"
  const val ID_REPLAYGAIN_PREAMP = "audio.replaygain_preamp"
  // Phase H.3 — sleep timer.
  const val ID_SLEEP_TIMER = "audio.sleep_timer"
  // Phase H.4 — system equalizer hand-off.
  const val ID_SYSTEM_EQUALIZER = "audio.system_equalizer"

  // Phase H.5 — playlist backup actions on the Settings root, in the
  // Library group alongside Music sources / Refresh / Rescan.
  const val ID_LIBRARY_EXPORT_PLAYLISTS = "library.export_playlists"
  const val ID_LIBRARY_IMPORT_PLAYLISTS = "library.import_playlists"

  /** R.F.14 — flat aggregation across the per-section files. */
  val entries: List<SettingsCatalogEntry> =
    RootEntries +
      LookAndFeelEntries +
      PersonalizeEntries +
      CoverArtEntries +
      ContentEntries +
      AudioEntries

  /** Look up an entry by id. Throws if missing — IDs are compile-time stable. */
  fun byId(id: String): SettingsCatalogEntry =
    entries.first { it.id == id }

  /** Entries belonging to one section, grouped and rendered in one card per group. */
  fun bySection(section: Section): List<SettingsCatalogEntry> =
    entries.filter { it.section == section }

  /**
   * Search the catalog. Case-insensitive substring match against
   * label, subtitle, and keywords. Empty query returns an empty list
   * (the search overlay shows an empty-state hint instead).
   */
  fun search(query: String): List<SettingsCatalogEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return entries.filter { entry ->
      entry.label.lowercase().contains(q) ||
        (entry.subtitle?.lowercase()?.contains(q) ?: false) ||
        entry.keywords.any { it.lowercase().contains(q) }
    }
  }

  /** Human-readable breadcrumb path joined with `>` for search result subtitles. */
  fun breadcrumbPath(entry: SettingsCatalogEntry): String =
    entry.breadcrumb.joinToString(" > ")
}
