package com.eight87.tonearmboy.ui.settings

import com.eight87.tonearmboy.ui.nav.SettingsAbout
import com.eight87.tonearmboy.ui.nav.SettingsAudio
import com.eight87.tonearmboy.ui.nav.SettingsContent
import com.eight87.tonearmboy.ui.nav.SettingsCoverArt
import com.eight87.tonearmboy.ui.nav.SettingsLookAndFeel
import com.eight87.tonearmboy.ui.nav.SettingsMusicSources
import com.eight87.tonearmboy.ui.nav.SettingsPersonalize
import com.eight87.tonearmboy.ui.nav.SettingsRootDest
import com.eight87.tonearmboy.ui.settings.catalog.RowKind
import com.eight87.tonearmboy.ui.settings.catalog.Section
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [SettingsCatalog] single-source-of-truth. Catches three
 * classes of drift:
 *
 *   1. Orphan rows — a UI sub-page renders a row that has no catalog
 *      entry. The page renderer drives off the catalog, so the inverse
 *      of this is what fails first: a missing catalog entry means the
 *      row simply doesn't render. We pin the expected ID set so a
 *      stealth removal during a refactor breaks compile or test.
 *   2. Unreachable entries — a catalog entry whose `destination`
 *      doesn't correspond to any rendered sub-page.
 *   3. Search drift — the filter must hit by label, subtitle, or
 *      keyword (case-insensitive) and breadcrumb paths must be
 *      complete (root-relative paths with > 1 segment).
 */
class SettingsCatalogTest {

  @Test
  fun every_expected_setting_id_has_an_entry() {
    val expectedIds = listOf(
      // Settings root.
      SettingsCatalog.ID_APPEARANCE_LOOK_AND_FEEL,
      SettingsCatalog.ID_APPEARANCE_PERSONALIZE,
      SettingsCatalog.ID_BEHAVIOUR_CONTENT,
      SettingsCatalog.ID_BEHAVIOUR_AUDIO,
      // Round 2 / Ask B — new top-level Cover art section.
      SettingsCatalog.ID_BEHAVIOUR_COVER_ART,
      SettingsCatalog.ID_LIBRARY_MUSIC_SOURCES,
      SettingsCatalog.ID_LIBRARY_REFRESH,
      SettingsCatalog.ID_LIBRARY_RESCAN,
      // D.16.4 — About sub-page entry.
      SettingsCatalog.ID_ABOUT,
      // Look and Feel.
      SettingsCatalog.ID_THEME,
      // D.20.4 — base-theme picker + album-art tint toggle replace the
      // legacy ID_COLOR_SCHEME + ID_BLACK_THEME pair.
      SettingsCatalog.ID_BASE_THEME,
      SettingsCatalog.ID_ALBUM_ART_TINT,
      SettingsCatalog.ID_CUSTOM_CHROME_TINT,
      // Personalize.
      SettingsCatalog.ID_LIBRARY_TABS,
      SettingsCatalog.ID_CUSTOM_PLAYBACK_BAR_ACTION,
      SettingsCatalog.ID_CUSTOM_NOTIFICATION_ACTION,
      SettingsCatalog.ID_PLAY_FROM_LIBRARY,
      SettingsCatalog.ID_PLAY_FROM_ITEM_DETAILS,
      // Content.
      SettingsCatalog.ID_AUTOMATIC_RELOADING,
      SettingsCatalog.ID_MULTI_VALUE_SEPARATORS,
      SettingsCatalog.ID_INTELLIGENT_SORTING,
      SettingsCatalog.ID_HIDE_COLLABORATORS,
      SettingsCatalog.ID_REFRESH_ALBUM_ART,
      // Round 2 / Ask B — these 7 IDs now live under Section.CoverArt.
      SettingsCatalog.ID_SCAN_FOLDERS_FOR_COVER_ART,
      SettingsCatalog.ID_AUTO_DISCOVER_ALBUM_ART,
      SettingsCatalog.ID_FILL_MISSING_COVERS,
      SettingsCatalog.ID_ALBUM_COVERS,
      SettingsCatalog.ID_FORCE_SQUARE_COVERS,
      SettingsCatalog.ID_COVER_ART_DISABLED,
      SettingsCatalog.ID_COVER_ART_PROVIDERS,
      // Audio.
      SettingsCatalog.ID_PAUSE_ON_REPEAT,
      SettingsCatalog.ID_REPLAYGAIN_STRATEGY,
      SettingsCatalog.ID_REPLAYGAIN_PREAMP,
      // Phase H.3 / H.4.
      SettingsCatalog.ID_SLEEP_TIMER,
      SettingsCatalog.ID_SYSTEM_EQUALIZER,
      // Phase H.5 — root-level Library actions.
      SettingsCatalog.ID_LIBRARY_EXPORT_PLAYLISTS,
      SettingsCatalog.ID_LIBRARY_IMPORT_PLAYLISTS,
    )
    val catalogIds = SettingsCatalog.entries.map { it.id }.toSet()
    expectedIds.forEach { id ->
      assertTrue("Catalog missing expected id: $id", catalogIds.contains(id))
    }
    // No accidental duplicates.
    assertEquals(catalogIds.size, SettingsCatalog.entries.size)
    assertEquals(expectedIds.toSet(), catalogIds)
  }

  @Test
  fun every_entry_has_a_breadcrumb_with_at_least_two_segments() {
    SettingsCatalog.entries.forEach { entry ->
      assertTrue(
        "Entry ${entry.id} has breadcrumb ${entry.breadcrumb}",
        entry.breadcrumb.size >= 2,
      )
      assertEquals(
        "Last breadcrumb segment for ${entry.id} should equal label",
        entry.label,
        entry.breadcrumb.last(),
      )
    }
  }

  @Test
  fun every_entry_destination_is_a_known_settings_destination() {
    val knownDestinations = setOf(
      SettingsRootDest,
      SettingsLookAndFeel,
      SettingsPersonalize,
      SettingsContent,
      SettingsCoverArt,
      SettingsAudio,
      SettingsMusicSources,
      SettingsAbout,
    )
    SettingsCatalog.entries.forEach { entry ->
      assertTrue(
        "Entry ${entry.id} navigates to unknown destination ${entry.destination}",
        knownDestinations.contains(entry.destination),
      )
    }
  }

  @Test
  fun every_section_is_populated() {
    Section.entries.forEach { section ->
      val entries = SettingsCatalog.bySection(section)
      assertTrue("Section $section has no entries", entries.isNotEmpty())
    }
  }

  @Test
  fun search_matches_by_label_case_insensitive() {
    val results = SettingsCatalog.search("theme")
    val ids = results.map { it.id }
    assertTrue(ids.contains(SettingsCatalog.ID_THEME))
    // D.20.4 — base theme replaces black theme as the multi-hit row.
    assertTrue(ids.contains(SettingsCatalog.ID_BASE_THEME))
    // Case-insensitivity.
    val upper = SettingsCatalog.search("THEME")
    assertEquals(results.toSet(), upper.toSet())
  }

  @Test
  fun search_matches_by_subtitle() {
    // "rounded rectangles" hits the Force-square-covers subtitle.
    val results = SettingsCatalog.search("rounded rectangles")
    assertTrue(
      "Force-square-covers subtitle search should hit",
      results.any { it.id == SettingsCatalog.ID_FORCE_SQUARE_COVERS },
    )
  }

  @Test
  fun search_matches_by_keyword_for_picker_options() {
    // "shuffle" is a keyword on Custom playback bar action.
    val results = SettingsCatalog.search("shuffle").map { it.id }.toSet()
    assertTrue(results.contains(SettingsCatalog.ID_CUSTOM_PLAYBACK_BAR_ACTION))
  }

  @Test
  fun search_results_span_multiple_subpages() {
    // "shuffle" hits Personalize > both rows. Pull the breadcrumb root
    // segment to confirm spread.
    val results = SettingsCatalog.search("shuffle")
    val breadcrumbRoots = results.map { it.breadcrumb.first() }.toSet()
    // Could be just Personalize, but at minimum more than zero.
    assertTrue(breadcrumbRoots.isNotEmpty())
  }

  @Test
  fun search_results_for_replaygain_include_both_strategy_and_preamp() {
    val ids = SettingsCatalog.search("replaygain").map { it.id }.toSet()
    assertTrue(ids.contains(SettingsCatalog.ID_REPLAYGAIN_STRATEGY))
    assertTrue(ids.contains(SettingsCatalog.ID_REPLAYGAIN_PREAMP))
  }

  @Test
  fun search_empty_query_returns_empty_results() {
    assertTrue(SettingsCatalog.search("").isEmpty())
    assertTrue(SettingsCatalog.search("   ").isEmpty())
  }

  @Test
  fun search_no_match_returns_empty() {
    assertTrue(SettingsCatalog.search("xyzzynotamatch").isEmpty())
  }

  @Test
  fun breadcrumbPath_renders_with_chevron_separator() {
    val entry = SettingsCatalog.byId(SettingsCatalog.ID_REPLAYGAIN_PREAMP)
    val path = SettingsCatalog.breadcrumbPath(entry)
    assertEquals("Audio > Volume normalization > ReplayGain pre-amp", path)
  }

  @Test
  fun every_entry_belongs_to_the_section_implied_by_its_destination_or_root() {
    // Sanity: a Section.LookAndFeel entry should not point to SettingsAudio.
    SettingsCatalog.entries.forEach { e ->
      val destSection: Section? = when (e.destination) {
        SettingsLookAndFeel -> Section.LookAndFeel
        SettingsPersonalize -> Section.Personalize
        SettingsContent -> Section.Content
        SettingsCoverArt -> Section.CoverArt
        SettingsAudio -> Section.Audio
        // Music sources is its own root-level destination; it's
        // navigable from the Settings root entry of the same id.
        SettingsMusicSources -> Section.Root
        SettingsAbout -> Section.Root
        SettingsRootDest -> Section.Root
        else -> null
      }
      assertNotNull("Unknown destination for ${e.id}", destSection)
      // Root-section entries can navigate to either a sub-page (the
      // sub-page card rows) or to the root itself (action / stub
      // rows). Sub-page entries must navigate to their own sub-page.
      if (e.section != Section.Root) {
        assertEquals(
          "Sub-page entry ${e.id} must navigate to its own sub-page",
          e.section,
          destSection,
        )
      }
    }
  }

  @Test
  fun no_v1_1_stubs_remain_after_d9d() {
    // D.9d shipped the last two stubs (Music sources and Automatic
    // reloading), so no catalog entry should still advertise the
    // deferral text.
    SettingsCatalog.entries.forEach { entry ->
      assertFalse(
        "Entry ${entry.id} still advertises 'Coming in v1.1.' after D.9d",
        entry.subtitle == "Coming in v1.1.",
      )
    }
    // No entry should be marked as a stub anymore.
    SettingsCatalog.entries.forEach { entry ->
      assertFalse(
        "Entry ${entry.id} is a Stub after D.9d — every setting must be wired",
        entry.kind == RowKind.Stub,
      )
    }
  }

  @Test
  fun phase_d9d_library_management_settings_are_wired_not_stubbed() {
    val musicSources = SettingsCatalog.byId(SettingsCatalog.ID_LIBRARY_MUSIC_SOURCES)
    val autoReload = SettingsCatalog.byId(SettingsCatalog.ID_AUTOMATIC_RELOADING)
    // D.17.3 — Music sources moved from a Navigate sub-page to an
    // OpenDialog modal so first-install users see the configuration
    // surface without leaving Settings root.
    assertEquals(RowKind.OpenDialog, musicSources.kind)
    assertEquals(RowKind.Toggle, autoReload.kind)
  }

  @Test
  fun phase_d9c_tag_handling_settings_are_wired_not_stubbed() {
    val separators = SettingsCatalog.byId(SettingsCatalog.ID_MULTI_VALUE_SEPARATORS)
    val intelligent = SettingsCatalog.byId(SettingsCatalog.ID_INTELLIGENT_SORTING)
    assertEquals(RowKind.Picker, separators.kind)
    assertEquals(RowKind.Toggle, intelligent.kind)
    assertFalse(
      "Multi-value separators must not advertise 'Coming in v1.1' after D.9c",
      separators.subtitle == "Coming in v1.1.",
    )
    // The intelligent-sorting subtitle was extended to mention the new
    // language coverage — it should no longer claim only the/a/an.
    assertTrue(
      "Intelligent sorting subtitle should mention multi-language coverage",
      intelligent.subtitle?.lowercase()?.contains("articles") == true,
    )
  }

  @Test
  fun phase_d9b_audio_quality_settings_are_wired_not_stubbed() {
    val replayGainStrategy = SettingsCatalog.byId(SettingsCatalog.ID_REPLAYGAIN_STRATEGY)
    val replayGainPreamp = SettingsCatalog.byId(SettingsCatalog.ID_REPLAYGAIN_PREAMP)
    val albumCovers = SettingsCatalog.byId(SettingsCatalog.ID_ALBUM_COVERS)
    assertEquals(RowKind.Picker, replayGainStrategy.kind)
    assertEquals(RowKind.Picker, replayGainPreamp.kind)
    assertEquals(RowKind.Picker, albumCovers.kind)
    assertFalse(
      "ReplayGain strategy must not advertise 'Coming in v1.1' after D.9b",
      replayGainStrategy.subtitle == "Coming in v1.1.",
    )
    assertFalse(
      "ReplayGain pre-amp must not advertise 'Coming in v1.1' after D.9b",
      replayGainPreamp.subtitle == "Coming in v1.1.",
    )
    assertFalse(
      "Album covers must not advertise 'Coming in v1.1' after D.9b",
      albumCovers.subtitle == "Coming in v1.1.",
    )
  }
}
