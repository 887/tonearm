package com.eight87.tonearmboy.ui.nav.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.util.UnstableApi
import com.eight87.tonearmboy.ui.nav.CustomTabEditor
import com.eight87.tonearmboy.ui.nav.LocalSectionTitle
import com.eight87.tonearmboy.ui.nav.RouteScope
import com.eight87.tonearmboy.ui.nav.SettingsAbout
import com.eight87.tonearmboy.ui.nav.SettingsAudio
import com.eight87.tonearmboy.ui.nav.SettingsContent
import com.eight87.tonearmboy.ui.nav.SettingsLicenses
import com.eight87.tonearmboy.ui.nav.SettingsLookAndFeel
import com.eight87.tonearmboy.ui.nav.SettingsMusicSources
import com.eight87.tonearmboy.ui.nav.SettingsPersonalize
import com.eight87.tonearmboy.ui.nav.SettingsRootDest
import com.eight87.tonearmboy.ui.nav.SettingsSearch
import com.eight87.tonearmboy.ui.settings.AboutScreen
import com.eight87.tonearmboy.ui.settings.LicensesScreen
import com.eight87.tonearmboy.ui.settings.SettingsAudioScreen
import com.eight87.tonearmboy.ui.settings.SettingsContentScreen
import com.eight87.tonearmboy.ui.settings.SettingsLookAndFeelScreen
import com.eight87.tonearmboy.ui.settings.SettingsPersonalizeScreen
import com.eight87.tonearmboy.ui.settings.SettingsScreen
import com.eight87.tonearmboy.ui.settings.catalog.LocalHighlightedSettingId
import com.eight87.tonearmboy.ui.settings.catalog.SettingsSearchScreen

/**
 * R.E.2 — `Register` extensions for every Settings sub-page.
 */

@Composable
fun SettingsRootDest.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "Settings" }
  with(scope) {
    SettingsScreen(
      onBack = { backStack.pop() },
      onLookAndFeel = { backStack.push(SettingsLookAndFeel) },
      onPersonalize = { backStack.push(SettingsPersonalize) },
      onContent = { backStack.push(SettingsContent) },
      onAudio = { backStack.push(SettingsAudio) },
      // D.17.3 — open the modal Music sources dialog instead of
      // pushing a sub-page. The dialog state lives at the app
      // level so search results landing on Settings root can
      // surface it the same way.
      onMusicSources = onShowMusicSourcesDialog,
      onRefreshMusic = onRefreshMusic,
      onRescanMusic = onRescanMusic,
      onExportPlaylists = playlistBackup.onExport,
      onImportPlaylists = playlistBackup.onImport,
      onAbout = { backStack.push(SettingsAbout) },
      onOpenSearch = { backStack.push(SettingsSearch) },
      snackbarHostState = snackbar,
    )
  }
}

@Composable
fun SettingsAbout.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "About" }
  with(scope) {
    AboutScreen(
      onBack = { backStack.pop() },
      onLicenses = { backStack.push(SettingsLicenses) },
      snackbarHostState = snackbar,
    )
  }
}

@Composable
fun SettingsLicenses.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "Open-source licenses" }
  with(scope) {
    LicensesScreen(onBack = { backStack.pop() })
  }
}

@Composable
fun SettingsSearch.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "Search settings" }
  val highlightedSettingId = LocalHighlightedSettingId.current
  with(scope) {
    SettingsSearchScreen(
      onBack = { backStack.pop() },
      onResult = { destination, id ->
        // Pop the search overlay, then push (or stay on) the
        // destination sub-page. Seed the highlight so the
        // matched row briefly flashes when it composes.
        highlightedSettingId.value = id
        backStack.pop()
        if (destination !is SettingsRootDest) {
          backStack.push(destination)
        }
      },
    )
  }
}

@Composable
fun SettingsLookAndFeel.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "Look and Feel" }
  with(scope) {
    SettingsLookAndFeelScreen(
      theme = graph.settingsRepository,
      onBack = { backStack.pop() },
      onComingSoon = onComingSoon,
      snackbarHostState = snackbar,
    )
  }
}

@Composable
fun SettingsPersonalize.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "Personalize" }
  with(scope) {
    SettingsPersonalizeScreen(
      playback = graph.settingsRepository,
      tabs = graph.settingsRepository,
      customTabStore = graph.customTabs,
      onBack = { backStack.pop() },
      onOpenCustomTabEditor = { id -> backStack.push(CustomTabEditor(id)) },
      onComingSoon = onComingSoon,
      snackbarHostState = snackbar,
    )
  }
}

@Composable
fun SettingsContent.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "Content" }
  with(scope) {
    SettingsContentScreen(
      library = graph.settingsRepository,
      onBack = { backStack.pop() },
      onComingSoon = onComingSoon,
      snackbarHostState = snackbar,
      onRefreshAlbumArt = onRefreshAlbumArt,
      onOpenCoverArtProviders = {
        backStack.push(com.eight87.tonearmboy.ui.nav.SettingsCoverArtProviders)
      },
    )
  }
}

/** Cover-art Phase D — reorderable provider list sub-page. */
@Composable
fun com.eight87.tonearmboy.ui.nav.SettingsCoverArtProviders.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "Cover art providers" }
  with(scope) {
    com.eight87.tonearmboy.ui.settings.CoverArtProvidersScreen(
      library = graph.settingsRepository,
      onBack = { backStack.pop() },
      onOpenPipedInstances = {
        backStack.push(com.eight87.tonearmboy.ui.nav.SettingsPipedInstances)
      },
      onOpenMusicBrainzSettings = {
        backStack.push(com.eight87.tonearmboy.ui.nav.SettingsMusicBrainzProvider)
      },
      snackbarHostState = snackbar,
    )
  }
}

/** Cover-art Phase D.3 — Piped instance list editor. */
@Composable
fun com.eight87.tonearmboy.ui.nav.SettingsPipedInstances.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "Piped instances" }
  with(scope) {
    com.eight87.tonearmboy.ui.settings.PipedInstancesScreen(
      library = graph.settingsRepository,
      onBack = { backStack.pop() },
      snackbarHostState = snackbar,
    )
  }
}

/** Cover-art Phase D — MusicBrainz match-threshold slider sub-page. */
@Composable
fun com.eight87.tonearmboy.ui.nav.SettingsMusicBrainzProvider.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "MusicBrainz" }
  with(scope) {
    com.eight87.tonearmboy.ui.settings.MusicBrainzProviderScreen(
      library = graph.settingsRepository,
      onBack = { backStack.pop() },
      snackbarHostState = snackbar,
    )
  }
}

@Composable
fun SettingsMusicSources.Register(scope: RouteScope) {
  // D.17.3 — SettingsMusicSources is no longer a navigable destination;
  // the row opens the MusicSourcesDialog at the app-root level instead.
  // The NavKey is kept (and registered with this no-op entry) so back-
  // stack save state and search routes that still reference it stay
  // valid; landing here pops to Settings root and surfaces the dialog.
  with(scope) {
    LaunchedEffect(Unit) {
      backStack.pop()
      onShowMusicSourcesDialog()
    }
  }
}

@OptIn(UnstableApi::class)
@Composable
fun SettingsAudio.Register(scope: RouteScope) {
  val sectionTitle = LocalSectionTitle.current
  LaunchedEffect(Unit) { sectionTitle.value = "Audio" }
  with(scope) {
    SettingsAudioScreen(
      settings = graph.settingsRepository,
      onBack = { backStack.pop() },
      onComingSoon = onComingSoon,
      snackbarHostState = snackbar,
      sleepTimer = graph.sleepTimer,
      nowPlayingState = playback,
    )
  }
}
