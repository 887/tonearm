package com.eight87.tonearmboy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.ui.settings.catalog.Section
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCard
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalog
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalogPage
import com.eight87.tonearmboy.ui.settings.catalog.SettingsDimens
import com.eight87.tonearmboy.ui.settings.catalog.SettingsRow
import com.eight87.tonearmboy.ui.settings.catalog.SettingsRowBinding
import com.eight87.tonearmboy.ui.settings.catalog.SettingsRowDivider
import com.eight87.tonearmboy.ui.settings.catalog.SettingsSearchBar
import com.eight87.tonearmboy.ui.settings.catalog.groupTitleFor

/**
 * Settings root. Top-level chrome:
 *
 *   - back arrow + "Settings" title in the [TopAppBar]
 *   - global search bar pinned just under it
 *   - grouped rounded cards: Appearance / Behaviour / Library
 *
 * The cards and rows come from [SettingsCatalog]. Tapping the search
 * bar pushes [com.eight87.tonearmboy.ui.nav.SettingsSearch] which holds
 * the full-screen search overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onLookAndFeel: () -> Unit,
  onPersonalize: () -> Unit,
  onContent: () -> Unit,
  onCoverArt: () -> Unit,
  onAudio: () -> Unit,
  onMusicSources: () -> Unit,
  onRefreshMusic: () -> Unit,
  onRescanMusic: () -> Unit,
  onExportPlaylists: () -> Unit,
  onImportPlaylists: () -> Unit,
  onAbout: () -> Unit,
  onOpenSearch: () -> Unit,
  snackbarHostState: SnackbarHostState,
) {
  var confirmRescan by remember { mutableStateOf(false) }

  // Bindings for every Section.Root entry. The page renderer falls back
  // to a stub render for entries with no binding, but every row at the
  // root has either a navigation tap (sub-page entries) or a leaf
  // action (refresh, rescan), or a stub (music sources).
  val bindings = listOf(
    SettingsRowBinding.Action(SettingsCatalog.ID_APPEARANCE_LOOK_AND_FEEL, onClick = onLookAndFeel),
    SettingsRowBinding.Action(SettingsCatalog.ID_APPEARANCE_PERSONALIZE, onClick = onPersonalize),
    SettingsRowBinding.Action(SettingsCatalog.ID_BEHAVIOUR_CONTENT, onClick = onContent),
    SettingsRowBinding.Action(SettingsCatalog.ID_BEHAVIOUR_AUDIO, onClick = onAudio),
    SettingsRowBinding.Action(SettingsCatalog.ID_BEHAVIOUR_COVER_ART, onClick = onCoverArt),
    SettingsRowBinding.Action(SettingsCatalog.ID_LIBRARY_MUSIC_SOURCES, onClick = onMusicSources),
    SettingsRowBinding.Action(SettingsCatalog.ID_LIBRARY_REFRESH, onClick = onRefreshMusic),
    SettingsRowBinding.Action(SettingsCatalog.ID_LIBRARY_RESCAN, onClick = { confirmRescan = true }),
    // Phase H.5 — export/import playlists.
    SettingsRowBinding.Action(SettingsCatalog.ID_LIBRARY_EXPORT_PLAYLISTS, onClick = onExportPlaylists),
    SettingsRowBinding.Action(SettingsCatalog.ID_LIBRARY_IMPORT_PLAYLISTS, onClick = onImportPlaylists),
    SettingsRowBinding.Action(SettingsCatalog.ID_ABOUT, onClick = onAbout),
  )

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
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
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(rememberScrollState())
        .semantics { testTag = "settings_screen" },
      verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardSpacing),
    ) {
      // Search bar: 16 dp horizontal inset, sits above the first card.
      SettingsSearchBar(
        onOpen = onOpenSearch,
        modifier = Modifier.padding(
          start = SettingsDimens.PagePadding,
          end = SettingsDimens.PagePadding,
          top = 12.dp,
        ),
      )

      // Grouped cards. We render the page inline (not via the
      // [SettingsCatalogPage] helper) so the search bar and the cards
      // share one scroll container.
      val pageEntries = SettingsCatalog.bySection(Section.Root)
      val grouped = pageEntries.groupBy { it.group }
      val bindingsById = bindings.associateBy { it.id }
      grouped.forEach { (group, items) ->
        SettingsCard(
          title = groupTitleFor(group),
          modifier = Modifier.padding(horizontal = SettingsDimens.PagePadding),
        ) {
          items.forEachIndexed { index, entry ->
            val binding = bindingsById[entry.id]
            val resolvedLabel = stringResource(entry.labelRes)
            val resolvedSubtitle = entry.subtitleRes?.let { stringResource(it) }
            // m3-expressive Phase C — pick a stable accent per row.
            val accent = com.eight87.tonearmboy.theme.accentFor(entry.id)
            when (binding) {
              is SettingsRowBinding.Action -> SettingsRow(
                id = entry.id,
                icon = entry.icon,
                label = resolvedLabel,
                subtitle = binding.subtitleOverride ?: resolvedSubtitle,
                onClick = binding.onClick,
                accent = accent,
              )
              else -> SettingsRow(
                id = entry.id,
                icon = entry.icon,
                label = resolvedLabel,
                subtitle = resolvedSubtitle,
                onClick = null,
                accent = accent,
              )
            }
            if (index < items.size - 1) SettingsRowDivider()
          }
        }
      }
    }
  }

  if (confirmRescan) {
    AlertDialog(
      onDismissRequest = { confirmRescan = false },
      title = { Text(stringResource(R.string.settings_rescan_dialog_title)) },
      text = { Text(stringResource(R.string.settings_rescan_dialog_text)) },
      confirmButton = {
        TextButton(onClick = { onRescanMusic(); confirmRescan = false }) {
          Text(stringResource(R.string.settings_rescan_dialog_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmRescan = false }) {
          Text(stringResource(R.string.settings_dialog_cancel))
        }
      },
    )
  }
}
