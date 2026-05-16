package com.eight87.tonearmboy.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.media3.common.util.UnstableApi
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.data.albumart.AlbumArtBulkWorker
import com.eight87.tonearmboy.ui.settings.catalog.Section
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalog
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalogPage
import com.eight87.tonearmboy.ui.settings.catalog.SettingsRowBinding
import kotlinx.coroutines.launch

/**
 * Round 2 / Ask B — Cover art top-level Settings sub-page. Renders the
 * seven cover-art rows lifted out of `SettingsContentScreen` so they
 * live one tap from the Settings root instead of buried inside
 * Content.
 *
 * Bindings mirror the patterns in `SettingsSubPages.kt`. The "Fill in
 * missing covers now" row navigates to [SettingsBulkArtProgressScreen]
 * (Round 2 / Ask A) — tapping it no longer silently enqueues a
 * WorkManager job; the user sees the worker's progress instead.
 */
@OptIn(UnstableApi::class)
@Composable
fun SettingsCoverArtScreen(
  library: LibrarySettings,
  onBack: () -> Unit,
  onOpenCoverArtProviders: () -> Unit,
  onOpenBulkArtProgress: () -> Unit,
  snackbarHostState: SnackbarHostState,
) {
  val albumCoversMode by library.albumCoversMode.flow.collectAsState(
    initial = AlbumCoversMode.Default,
  )
  val forceSquareCovers by library.forceSquareCovers.flow.collectAsState(initial = false)
  val autoDiscoverAlbumArt by library.autoDiscoverAlbumArt.flow.collectAsState(initial = false)
  val scanFoldersForCoverArt by library.scanFoldersForCoverArt.flow.collectAsState(initial = true)
  val coverArtDisabled by library.coverArtDisabled.flow.collectAsState(initial = false)
  val coverArtProviders by library.coverArtProviders.flow.collectAsState(
    initial = com.eight87.tonearmboy.data.albumart.ProviderListCodec.DEFAULT,
  )
  val scope = rememberCoroutineScope()
  val albumCoversPicker = rememberSettingPickerState()
  val context = LocalContext.current

  val bindings = listOf(
    SettingsRowBinding.Toggle(
      id = SettingsCatalog.ID_COVER_ART_DISABLED,
      checked = coverArtDisabled,
      onCheckedChange = { scope.launch { library.coverArtDisabled.set(it) } },
    ),
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_COVER_ART_PROVIDERS,
      currentLabel = activeProvidersSummary(coverArtProviders, coverArtDisabled),
      onClick = onOpenCoverArtProviders,
    ),
    // Round 2 / Ask A — navigates to the progress sub-page rather than
    // fire-and-forget enqueueing. The sub-page exposes its own "Start
    // now" button so the kill-switch / no-providers branches surface
    // as visible log entries rather than silent no-ops.
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_FILL_MISSING_COVERS,
      currentLabel = "",
      onClick = onOpenBulkArtProgress,
    ),
    SettingsRowBinding.Toggle(
      id = SettingsCatalog.ID_AUTO_DISCOVER_ALBUM_ART,
      checked = autoDiscoverAlbumArt,
      onCheckedChange = { value ->
        scope.launch { library.autoDiscoverAlbumArt.set(value) }
        if (value) {
          enqueueBulkArtWorker(context, replace = false)
        } else {
          androidx.work.WorkManager.getInstance(context).cancelUniqueWork(
            AlbumArtBulkWorker.UNIQUE_WORK_NAME,
          )
        }
      },
    ),
    SettingsRowBinding.Toggle(
      id = SettingsCatalog.ID_SCAN_FOLDERS_FOR_COVER_ART,
      checked = scanFoldersForCoverArt,
      onCheckedChange = { scope.launch { library.scanFoldersForCoverArt.set(it) } },
    ),
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_ALBUM_COVERS,
      currentLabel = albumCoversLabel(context, albumCoversMode),
      onClick = albumCoversPicker::show,
    ),
    SettingsRowBinding.Toggle(
      id = SettingsCatalog.ID_FORCE_SQUARE_COVERS,
      checked = forceSquareCovers,
      onCheckedChange = { scope.launch { library.forceSquareCovers.set(it) } },
    ),
  )

  CoverArtSubScaffold(
    title = stringResource(R.string.settings_cover_art_title),
    testTagName = "settings_cover_art",
    onBack = onBack,
    snackbarHostState = snackbarHostState,
  ) { mod ->
    SettingsCatalogPage(
      testTagName = "settings_cover_art_body",
      section = Section.CoverArt,
      bindings = bindings,
      modifier = mod,
    )
  }

  albumCoversPicker.Render(
    title = stringResource(R.string.settings_content_album_covers_label),
    options = AlbumCoversMode.entries,
    label = { albumCoversLabel(context, it) },
    current = albumCoversMode,
    onPick = { scope.launch { library.albumCoversMode.set(it) } },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverArtSubScaffold(
  title: String,
  testTagName: String,
  onBack: () -> Unit,
  snackbarHostState: SnackbarHostState,
  body: @Composable (Modifier) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.settings_cd_back),
            )
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    body(
      Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .semantics { testTag = testTagName },
    )
  }
}
