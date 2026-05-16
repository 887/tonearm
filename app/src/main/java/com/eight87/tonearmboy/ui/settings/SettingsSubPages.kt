package com.eight87.tonearmboy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.ui.settings.catalog.Section
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalog
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCatalogPage
import com.eight87.tonearmboy.ui.settings.catalog.SettingsRowBinding
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch

// =============================================================================
// Shared sub-page scaffold: back arrow + section title + catalog-driven body.
// Sub-pages do NOT host their own search bar — search is global, lives only at
// the Settings root.
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubScaffold(
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

// =============================================================================
// Look and Feel
// =============================================================================

@Composable
fun SettingsLookAndFeelScreen(
  theme: ThemeSettings,
  onBack: () -> Unit,
  onComingSoon: (String) -> Unit,
  snackbarHostState: SnackbarHostState,
) {
  val themePref by theme.theme.flow.collectAsState(initial = ThemePreference.Default)
  val baseTheme by theme.baseTheme.flow.collectAsState(initial = BaseTheme.Default)
  val albumArtTintEnabled by theme.albumArtTintEnabled.flow.collectAsState(
    initial = true,
  )
  val customChromeTint by theme.customChromeTint.flow.collectAsState(initial = 0L)
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  // R.F.17 — picker state controllers (Settings-F5).
  val themePicker = rememberSettingPickerState()
  val baseThemePicker = rememberSettingPickerState()
  var colorPicker by remember { mutableStateOf(false) }
  var chromeTintPicker by remember { mutableStateOf(false) }

  // D.25.1 — when Custom is the active base theme, render a coloured
  // swatch trailing the row so the user sees what they picked at a
  // glance. Uses the seed RGB straight, alpha 1.
  val customSwatch: (@Composable () -> Unit)? = (baseTheme as? BaseTheme.Custom)?.let { custom ->
    {
      Box(
        modifier = Modifier
          .size(24.dp)
          .clip(CircleShape)
          .background(Color(0xFF000000L or custom.seedRgb))
          .semantics { testTag = "base_theme_custom_swatch" },
      )
    }
  }

  val bindings = listOf(
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_THEME,
      currentLabel = themeLabel(context, themePref),
      onClick = themePicker::show,
    ),
    // D.20.4 / D.25.1 — base-theme picker. Custom-color is a fourth
    // option that opens a real colour picker; the trailing swatch
    // surfaces the picked seed when Custom is active.
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_BASE_THEME,
      currentLabel = baseThemeLabel(context, baseTheme),
      onClick = baseThemePicker::show,
      trailing = customSwatch,
    ),
    SettingsRowBinding.Toggle(
      id = SettingsCatalog.ID_ALBUM_ART_TINT,
      checked = albumArtTintEnabled,
      onCheckedChange = { scope.launch { theme.albumArtTintEnabled.set(it) } },
    ),
    // Custom chrome tint colour — picker. Subtitle reflects the current
    // state: hex value when set, "Album art" when unset (the
    // album-art-derived tint is the default fallback). Trailing swatch
    // shows the picked colour at a glance, like the base-theme custom
    // swatch above.
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_CUSTOM_CHROME_TINT,
      currentLabel = if (customChromeTint == 0L)
        stringResource(R.string.settings_custom_chrome_tint_unset)
      else "#%06X".format(customChromeTint),
      onClick = { chromeTintPicker = true },
      trailing = if (customChromeTint != 0L) {
        {
          Box(
            modifier = Modifier
              .size(24.dp)
              .clip(CircleShape)
              .background(Color(0xFF000000L or customChromeTint))
              .semantics { testTag = "custom_chrome_tint_swatch" },
          )
        }
      } else null,
    ),
  )

  SettingsSubScaffold(
    stringResource(R.string.settings_lookandfeel_title),
    "settings_look_and_feel",
    onBack,
    snackbarHostState,
  ) { mod ->
    SettingsCatalogPage(
      testTagName = "settings_look_and_feel_body",
      section = Section.LookAndFeel,
      bindings = bindings,
      modifier = mod,
    )
  }

  themePicker.Render(
    title = stringResource(R.string.settings_lookandfeel_theme_label),
    options = ThemePreference.entries,
    label = { themeLabel(context, it) },
    current = themePref,
    onPick = { scope.launch { theme.theme.set(it) } },
  )
  // D.25.1 — base-theme picker. Tapping Custom hands off to the colour
  // picker; the other three commit immediately like before.
  baseThemePicker.Render(
    title = stringResource(R.string.settings_lookandfeel_base_theme_label),
    options = baseThemePickerOptions,
    label = { baseThemeLabel(context, it) },
    current = baseThemeMatch(baseTheme),
    onPick = { picked ->
      if (picked is BaseTheme.Custom) {
        colorPicker = true
      } else {
        scope.launch { theme.baseTheme.set(picked) }
      }
    },
  )
  if (colorPicker) {
    // Re-open from the saved seed when the user already had Custom
    // selected; otherwise fall back to the placeholder Material 3
    // purple so the picker has a sensible starting point.
    val initialSeed = (baseTheme as? BaseTheme.Custom)?.seedRgb ?: 0x6750A4L
    ColorPickerDialog(
      initialRgb = initialSeed,
      onConfirm = { rgb ->
        scope.launch { theme.baseTheme.set(BaseTheme.Custom(rgb)) }
        colorPicker = false
      },
      onDismiss = { colorPicker = false },
    )
  }
  if (chromeTintPicker) {
    // Custom chrome tint picker — re-uses the D.25.1 dialog. Default
    // seed is a soft indigo when no tint is set yet so the picker
    // opens on a sensible starting point. Picking 0 isn't supported
    // by the dialog (no Reset button there), so we surface the
    // "Reset to album art" affordance via long-press on the row —
    // wired below as a trailing X swatch when set.
    val initial = if (customChromeTint == 0L) 0x6464C8L else customChromeTint
    ColorPickerDialog(
      initialRgb = initial,
      onConfirm = { rgb ->
        scope.launch { theme.customChromeTint.set(rgb) }
        chromeTintPicker = false
      },
      onDismiss = { chromeTintPicker = false },
    )
  }
}

internal fun themeLabel(context: android.content.Context, p: ThemePreference): String =
  context.getString(
    when (p) {
      ThemePreference.System -> R.string.settings_lookandfeel_theme_automatic
      ThemePreference.Light -> R.string.settings_lookandfeel_theme_light
      ThemePreference.Dark -> R.string.settings_lookandfeel_theme_dark
    },
  )

internal fun baseThemeLabel(context: android.content.Context, b: BaseTheme): String =
  context.getString(
    when (b) {
      is BaseTheme.DefaultAndroid -> R.string.settings_lookandfeel_base_theme_default_android
      is BaseTheme.DefaultColors -> R.string.settings_lookandfeel_base_theme_default_colors
      is BaseTheme.PureBlack -> R.string.settings_lookandfeel_base_theme_pure_black
      is BaseTheme.Custom -> R.string.settings_lookandfeel_base_theme_custom
    },
  )

// =============================================================================
// Personalize
// =============================================================================

@Composable
fun SettingsPersonalizeScreen(
  playback: PlaybackSettings,
  tabs: TabLayoutSettings,
  customTabStore: com.eight87.tonearmboy.data.CustomTabStore,
  onBack: () -> Unit,
  /**
   * D.30.3 — open the full-screen custom-tab editor route. `null` =
   * create new; non-null = edit existing tab id. The editor's old
   * inline `ModalBottomSheet` is gone.
   */
  onOpenCustomTabEditor: (Long?) -> Unit,
  onComingSoon: (String) -> Unit,
  snackbarHostState: SnackbarHostState,
) {
  val libraryTabs by tabs.libraryTabs.flow.collectAsState(initial = LibraryTab.DefaultOrder)
  val customBarAction by playback.customBarAction.flow.collectAsState(
    initial = CustomBarAction.Default,
  )
  val customNotificationAction by playback.customNotificationAction.flow.collectAsState(
    initial = CustomNotificationAction.Default,
  )
  val playFromLibrary by playback.playFromLibrary.flow.collectAsState(
    initial = PlayFromLibrary.Default,
  )
  val playFromItemDetails by playback.playFromItemDetails.flow.collectAsState(
    initial = PlayFromItemDetails.Default,
  )
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  // R.F.17 — picker state controllers (Settings-F5).
  var showLibraryTabs by remember { mutableStateOf(false) }
  val customBarPicker = rememberSettingPickerState()
  val customNotifPicker = rememberSettingPickerState()
  val playFromLibPicker = rememberSettingPickerState()
  val playFromDetailPicker = rememberSettingPickerState()

  val bindings = listOf(
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_LIBRARY_TABS,
      currentLabel = describeLibraryTabs(context, libraryTabs),
      onClick = { showLibraryTabs = true },
    ),
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_CUSTOM_PLAYBACK_BAR_ACTION,
      currentLabel = customBarActionLabel(context, customBarAction),
      onClick = customBarPicker::show,
    ),
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_CUSTOM_NOTIFICATION_ACTION,
      currentLabel = customNotificationActionLabel(context, customNotificationAction),
      onClick = customNotifPicker::show,
    ),
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_PLAY_FROM_LIBRARY,
      currentLabel = playFromLibraryLabel(context, playFromLibrary),
      onClick = playFromLibPicker::show,
    ),
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_PLAY_FROM_ITEM_DETAILS,
      currentLabel = playFromItemDetailsLabel(context, playFromItemDetails),
      onClick = playFromDetailPicker::show,
    ),
  )

  SettingsSubScaffold(
    stringResource(R.string.settings_personalize_title),
    "settings_personalize",
    onBack,
    snackbarHostState,
  ) { mod ->
    SettingsCatalogPage(
      testTagName = "settings_personalize_body",
      section = Section.Personalize,
      bindings = bindings,
      modifier = mod,
    )
  }

  if (showLibraryTabs) {
    val customTabs by customTabStore.customTabs().collectAsState(initial = emptyList())
    LibraryTabsDialog(
      model = LibraryTabsDialogModel(
        builtIns = LibraryTab.entries.toList(),
        visibleSet = libraryTabs.toSet(),
        customTabs = customTabs,
      ),
      onDismiss = { showLibraryTabs = false },
      onSetBuiltInVisibility = { tab, visible ->
        // Reflect visibility into the persisted libraryTabs while
        // keeping the saved order. Add to / remove from the list.
        val current = libraryTabs.toMutableList()
        if (visible) {
          if (tab !in current) current.add(tab)
        } else {
          current.remove(tab)
        }
        scope.launch { tabs.libraryTabs.set(current) }
      },
      onReorderBuiltIns = { newOrder ->
        scope.launch { tabs.libraryTabs.set(newOrder) }
      },
      onReorderCustomTabs = { orderedIds ->
        scope.launch { customTabStore.reorderCustomTabs(orderedIds) }
      },
      // D.30.3 — Add / Edit dismiss the dialog and push the full-screen
      // editor route. The dialog is rebuilt fresh when the user pops
      // back, picking up any newly-saved tab from the live customTabs
      // Flow.
      onAddCustomTab = {
        showLibraryTabs = false
        onOpenCustomTabEditor(null)
      },
      onEditCustomTab = { tab ->
        showLibraryTabs = false
        onOpenCustomTabEditor(tab.id)
      },
      onDeleteCustomTab = { tab ->
        scope.launch { customTabStore.deleteCustomTab(tab.id) }
      },
    )
  }
  customBarPicker.Render(
    title = stringResource(R.string.settings_personalize_custom_bar_action_label),
    options = CustomBarAction.entries,
    label = { customBarActionLabel(context, it) },
    current = customBarAction,
    onPick = { scope.launch { playback.customBarAction.set(it) } },
  )
  customNotifPicker.Render(
    title = stringResource(R.string.settings_personalize_custom_notification_action_label),
    options = CustomNotificationAction.entries,
    label = { customNotificationActionLabel(context, it) },
    current = customNotificationAction,
    onPick = { scope.launch { playback.customNotificationAction.set(it) } },
  )
  playFromLibPicker.Render(
    title = stringResource(R.string.settings_personalize_play_from_library_label),
    options = PlayFromLibrary.entries,
    label = { playFromLibraryLabel(context, it) },
    current = playFromLibrary,
    onPick = { scope.launch { playback.playFromLibrary.set(it) } },
  )
  playFromDetailPicker.Render(
    title = stringResource(R.string.settings_personalize_play_from_item_details_label),
    options = PlayFromItemDetails.entries,
    label = { playFromItemDetailsLabel(context, it) },
    current = playFromItemDetails,
    onPick = { scope.launch { playback.playFromItemDetails.set(it) } },
  )
}

internal fun customBarActionLabel(context: android.content.Context, action: CustomBarAction): String =
  context.getString(
    when (action) {
      CustomBarAction.SkipNext -> R.string.settings_personalize_bar_action_skip_next
      CustomBarAction.ShuffleToggle -> R.string.settings_personalize_bar_action_shuffle_toggle
      CustomBarAction.RepeatToggle -> R.string.settings_personalize_bar_action_repeat_toggle
      CustomBarAction.None -> R.string.settings_personalize_bar_action_none
    },
  )

internal fun customNotificationActionLabel(
  context: android.content.Context,
  action: CustomNotificationAction,
): String = context.getString(
  when (action) {
    CustomNotificationAction.RepeatMode -> R.string.settings_personalize_notification_action_repeat
    CustomNotificationAction.Shuffle -> R.string.settings_personalize_notification_action_shuffle
    CustomNotificationAction.None -> R.string.settings_personalize_notification_action_none
  },
)

internal fun playFromLibraryLabel(context: android.content.Context, value: PlayFromLibrary): String =
  context.getString(
    when (value) {
      PlayFromLibrary.AllSongs -> R.string.settings_personalize_play_from_library_all_songs
      PlayFromLibrary.ItemOnly -> R.string.settings_personalize_play_from_library_item_only
      PlayFromLibrary.CurrentFilter -> R.string.settings_personalize_play_from_library_current_filter
    },
  )

internal fun playFromItemDetailsLabel(
  context: android.content.Context,
  value: PlayFromItemDetails,
): String = context.getString(
  when (value) {
    PlayFromItemDetails.ShownItem -> R.string.settings_personalize_play_from_item_shown
    PlayFromItemDetails.Album -> R.string.settings_personalize_play_from_item_album
    PlayFromItemDetails.Artist -> R.string.settings_personalize_play_from_item_artist
  },
)

private fun describeLibraryTabs(context: android.content.Context, tabs: List<LibraryTab>): String {
  if (tabs.isEmpty()) return context.getString(R.string.settings_personalize_library_tabs_default)
  return tabs.joinToString(" · ") { it.name }
}

// D.18.3 — `LibraryTabsEditor` was replaced by [LibraryTabsDialog]
// in `LibraryTabsDialog.kt`, which adds custom-tab CRUD plus
// drag-and-drop reorder.

// =============================================================================
// Content
// =============================================================================

@OptIn(UnstableApi::class)
@Composable
fun SettingsContentScreen(
  library: LibrarySettings,
  onBack: () -> Unit,
  onComingSoon: (String) -> Unit,
  snackbarHostState: SnackbarHostState,
  // album-art — drop Coil's caches; bound to the Refresh album art row.
  onRefreshAlbumArt: () -> Unit = {},
  // R.E.7 — injectable side-effect launchers (Settings-F6).
  autoReload: AutoReloadController = DefaultAutoReloadController,
) {
  val automaticReloading by library.automaticReloading.flow.collectAsState(
    initial = false,
  )
  val multiValueSeparators by library.multiValueSeparators.flow.collectAsState(
    initial = MultiValueSeparator.Default,
  )
  val intelligentSorting by library.intelligentSorting.flow.collectAsState(
    initial = true,
  )
  val hideCollaborators by library.hideCollaborators.flow.collectAsState(
    initial = false,
  )
  val scope = rememberCoroutineScope()
  // Round 2 / Ask B — every cover-art binding moved to SettingsCoverArtScreen.
  var separatorsPicker by remember { mutableStateOf(false) }
  val context = LocalContext.current

  val bindings = listOf(
    // D.9d.2 — Automatic reloading toggle. Persists immediately and
    // starts / stops the foreground watcher service in the same gesture
    // so the user sees the sticky notification appear within ~1 frame.
    SettingsRowBinding.Toggle(
      id = SettingsCatalog.ID_AUTOMATIC_RELOADING,
      checked = automaticReloading,
      onCheckedChange = { value ->
        scope.launch { library.automaticReloading.set(value) }
        autoReload.setEnabled(context, value)
      },
    ),
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_MULTI_VALUE_SEPARATORS,
      currentLabel = multiValueSeparatorsLabel(context, multiValueSeparators),
      onClick = { separatorsPicker = true },
    ),
    SettingsRowBinding.Toggle(
      id = SettingsCatalog.ID_INTELLIGENT_SORTING,
      checked = intelligentSorting,
      onCheckedChange = { scope.launch { library.intelligentSorting.set(it) } },
    ),
    SettingsRowBinding.Toggle(
      id = SettingsCatalog.ID_HIDE_COLLABORATORS,
      checked = hideCollaborators,
      onCheckedChange = { scope.launch { library.hideCollaborators.set(it) } },
    ),
    SettingsRowBinding.Action(
      id = SettingsCatalog.ID_REFRESH_ALBUM_ART,
      onClick = onRefreshAlbumArt,
    ),
  )

  SettingsSubScaffold(
    stringResource(R.string.settings_content_title),
    "settings_content",
    onBack,
    snackbarHostState,
  ) { mod ->
    SettingsCatalogPage(
      testTagName = "settings_content_body",
      section = Section.Content,
      bindings = bindings,
      modifier = mod,
    )
  }

  if (separatorsPicker) {
    MultiSelectPicker(
      title = stringResource(R.string.settings_content_multi_value_separators_label),
      options = MultiValueSeparator.entries,
      label = { multiValueSeparatorOptionLabel(context, it) },
      currentSelection = multiValueSeparators,
      onSave = { newSelection ->
        scope.launch { library.multiValueSeparators.set(newSelection) }
        separatorsPicker = false
        if (newSelection != multiValueSeparators) {
          scope.launch {
            snackbarHostState.showSnackbar(
              context.getString(R.string.settings_content_separators_change_applied),
            )
          }
        }
      },
      onDismiss = { separatorsPicker = false },
    )
  }
}

internal fun multiValueSeparatorOptionLabel(
  context: android.content.Context,
  s: MultiValueSeparator,
): String = context.getString(
  when (s) {
    MultiValueSeparator.Semicolon -> R.string.settings_content_separators_semicolon
    MultiValueSeparator.Slash -> R.string.settings_content_separators_slash
    MultiValueSeparator.Comma -> R.string.settings_content_separators_comma
    MultiValueSeparator.Ampersand -> R.string.settings_content_separators_ampersand
    MultiValueSeparator.Feat -> R.string.settings_content_separators_feat
    MultiValueSeparator.Ft -> R.string.settings_content_separators_ft
  },
)

internal fun multiValueSeparatorsLabel(
  context: android.content.Context,
  selection: Set<MultiValueSeparator>,
): String {
  if (selection.isEmpty()) return context.getString(R.string.settings_content_separators_off)
  // Surface the literal tokens so the row preview matches what the user
  // selected. Sort by enum order to keep the preview deterministic.
  return selection.sortedBy { it.ordinal }.joinToString("  ") { it.token }
}

internal fun albumCoversLabel(context: android.content.Context, mode: AlbumCoversMode): String =
  context.getString(
    when (mode) {
      AlbumCoversMode.Balanced -> R.string.settings_content_album_covers_balanced
      AlbumCoversMode.On -> R.string.settings_content_album_covers_on
      AlbumCoversMode.Off -> R.string.settings_content_album_covers_off
    },
  )

// =============================================================================
// Audio
// =============================================================================

@OptIn(UnstableApi::class)
@Composable
fun SettingsAudioScreen(
  settings: PlaybackSettings,
  onBack: () -> Unit,
  onComingSoon: (String) -> Unit,
  snackbarHostState: SnackbarHostState,
  // Phase H.3 / R.C.5 — sleep-timer surface. Nullable so existing
  // tests / preview surfaces can instantiate without it; null falls
  // back to a "Coming in v1.1" snackbar on row tap.
  sleepTimer: com.eight87.tonearmboy.playback.SleepTimer? = null,
  // Phase H.4 — system equalizer needs the active audio session id.
  // Read from the NowPlayingState facet (audioSessionId is exposed
  // there). Nullable for the same test reason as `sleepTimer`.
  nowPlayingState: com.eight87.tonearmboy.playback.NowPlayingState? = null,
  // R.E.7 — injectable equalizer launcher (Settings-F6).
  equalizer: EqualizerLauncher = DefaultEqualizerLauncher,
) {
  val pauseOnRepeat by settings.pauseOnRepeat.flow.collectAsState(
    initial = false,
  )
  val replayGainStrategy by settings.replayGainStrategy.flow.collectAsState(
    initial = ReplayGainStrategy.Default,
  )
  val replayGainPreampDb by settings.replayGainPreampDb.flow.collectAsState(
    initial = 0f,
  )
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  // R.F.17 — picker state controller (Settings-F5).
  val rgStrategyPicker = rememberSettingPickerState()
  var rgPreampDialog by remember { mutableStateOf(false) }
  var sleepDialog by remember { mutableStateOf(false) }

  // Phase H.3 — observe the timer state so the dialog (and the row
  // subtitle) can render the live countdown without polling.
  val sleepState by (sleepTimer?.state
    ?: kotlinx.coroutines.flow.MutableStateFlow(
      com.eight87.tonearmboy.playback.SleepTimerState.Idle,
    )).collectAsState(initial = com.eight87.tonearmboy.playback.SleepTimerState.Idle)

  val sleepRowSubtitle = when (val s = sleepState) {
    is com.eight87.tonearmboy.playback.SleepTimerState.Running -> {
      val minutes = (s.remainingMs / 60_000L).coerceAtLeast(0)
      stringResource(R.string.settings_audio_sleep_timer_running_subtitle, minutes.toInt())
    }
    com.eight87.tonearmboy.playback.SleepTimerState.WaitingForTrackEnd ->
      stringResource(R.string.settings_audio_sleep_timer_waiting_subtitle)
    com.eight87.tonearmboy.playback.SleepTimerState.Idle ->
      stringResource(R.string.settings_audio_sleep_timer_subtitle)
  }

  val bindings = listOf(
    SettingsRowBinding.Toggle(
      id = SettingsCatalog.ID_PAUSE_ON_REPEAT,
      checked = pauseOnRepeat,
      onCheckedChange = { scope.launch { settings.pauseOnRepeat.set(it) } },
    ),
    // Phase H.3 — sleep timer row.
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_SLEEP_TIMER,
      currentLabel = sleepRowSubtitle,
      onClick = { sleepDialog = true },
    ),
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_REPLAYGAIN_STRATEGY,
      currentLabel = replayGainStrategyLabel(context, replayGainStrategy),
      onClick = rgStrategyPicker::show,
    ),
    SettingsRowBinding.Picker(
      id = SettingsCatalog.ID_REPLAYGAIN_PREAMP,
      currentLabel = formatPreampDb(replayGainPreampDb),
      onClick = { rgPreampDialog = true },
    ),
    // Phase H.4 — system equalizer row.
    SettingsRowBinding.Action(
      id = SettingsCatalog.ID_SYSTEM_EQUALIZER,
      onClick = {
        val sessionId = nowPlayingState?.audioSessionId?.value
          ?: androidx.media3.common.C.AUDIO_SESSION_ID_UNSET
        if (!equalizer.launch(context, sessionId)) {
          scope.launch {
            snackbarHostState.showSnackbar(
              context.getString(R.string.error_settings_no_system_equalizer),
            )
          }
        }
      },
    ),
  )

  SettingsSubScaffold(
    stringResource(R.string.settings_audio_title),
    "settings_audio",
    onBack,
    snackbarHostState,
  ) { mod ->
    SettingsCatalogPage(
      testTagName = "settings_audio_body",
      section = Section.Audio,
      bindings = bindings,
      modifier = mod,
    )
  }

  if (sleepDialog) {
    SleepTimerDialog(
      state = sleepState,
      onStart = { durationMs, waitForEndOfTrack ->
        sleepTimer?.start(durationMs, waitForEndOfTrack)
        sleepDialog = false
        scope.launch {
          val mins = durationMs / 60_000L
          snackbarHostState.showSnackbar(
            context.getString(R.string.settings_audio_sleep_timer_set_snackbar, mins.toInt()),
          )
        }
      },
      onCancel = {
        sleepTimer?.cancel()
      },
      onDismiss = { sleepDialog = false },
    )
  }

  rgStrategyPicker.Render(
    title = stringResource(R.string.settings_audio_replaygain_strategy_label),
    options = ReplayGainStrategy.entries,
    label = { replayGainStrategyLabel(context, it) },
    current = replayGainStrategy,
    onPick = { scope.launch { settings.replayGainStrategy.set(it) } },
  )
  if (rgPreampDialog) {
    ReplayGainPreampDialog(
      currentDb = replayGainPreampDb,
      onConfirm = { newDb ->
        scope.launch { settings.replayGainPreampDb.set(newDb) }
        rgPreampDialog = false
      },
      onDismiss = { rgPreampDialog = false },
    )
  }
}

internal fun replayGainStrategyLabel(
  context: android.content.Context,
  s: ReplayGainStrategy,
): String = context.getString(
  when (s) {
    ReplayGainStrategy.Off -> R.string.settings_audio_replaygain_off
    ReplayGainStrategy.Track -> R.string.settings_audio_replaygain_track
    ReplayGainStrategy.Album -> R.string.settings_audio_replaygain_album
    ReplayGainStrategy.Smart -> R.string.settings_audio_replaygain_smart
  },
)

/** Format a pre-amp dB value with one decimal and an explicit sign. */
internal fun formatPreampDb(dB: Float): String {
  val rounded = (Math.round(dB * 10.0).toFloat()) / 10f
  val sign = if (rounded > 0) "+" else if (rounded < 0) "-" else ""
  val mag = kotlin.math.abs(rounded)
  return "$sign${"%.1f".format(mag)} dB"
}

/**
 * D.9b.2 — slider dialog for the pre-amp. Shows the current value
 * live as the user drags. "Reset" snaps back to 0 dB. Save persists
 * via [SettingsRepository.setReplayGainPreampDb], which clamps and
 * snaps to the 0.1 dB grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplayGainPreampDialog(
  currentDb: Float,
  onConfirm: (Float) -> Unit,
  onDismiss: () -> Unit,
) {
  var value by remember(currentDb) { mutableStateOf(currentDb) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.settings_audio_replaygain_preamp_dialog_title)) },
    text = {
      Column(modifier = Modifier.semantics { testTag = "rg_preamp_dialog" }) {
        Text(
          text = formatPreampDb(value),
          style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
        )
        Text(
          text = stringResource(R.string.settings_audio_replaygain_preamp_dialog_text),
          style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 4.dp),
        )
        Slider(
          value = value,
          onValueChange = { value = it },
          valueRange = SettingsRepository.REPLAYGAIN_PREAMP_MIN_DB..SettingsRepository.REPLAYGAIN_PREAMP_MAX_DB,
          // (max - min) / step - 1 internal stops; 0.1 dB grid over
          // 30 dB == 299 internal steps.
          steps = ((SettingsRepository.REPLAYGAIN_PREAMP_MAX_DB - SettingsRepository.REPLAYGAIN_PREAMP_MIN_DB) /
            SettingsRepository.REPLAYGAIN_PREAMP_STEP_DB).toInt() - 1,
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .semantics { testTag = "rg_preamp_slider" },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
          TextButton(onClick = { value = 0f }) {
            Text(stringResource(R.string.settings_audio_replaygain_preamp_reset))
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(value) }) {
        Text(stringResource(R.string.settings_dialog_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.settings_dialog_cancel))
      }
    },
  )
}

/**
 * Label for the [CoverArtService] picker — terse, user-readable.
 */
internal fun coverArtServiceLabel(s: CoverArtService): String = when (s) {
  CoverArtService.Disabled -> "None (no web requests)"
  CoverArtService.MusicBrainz -> "MusicBrainz / Cover Art Archive"
  CoverArtService.ITunes -> "Apple iTunes Search"
}

/**
 * Cover-art Phase D — summary string for the [SettingsCatalog.ID_COVER_ART_PROVIDERS]
 * row. "Off" when the kill switch is on; otherwise the active
 * providers listed in priority order; "No providers" when every
 * entry is toggled off.
 */
internal fun activeProvidersSummary(
  configs: List<com.eight87.tonearmboy.data.albumart.ProviderConfig>,
  disabled: Boolean,
): String {
  if (disabled) return "Off"
  val active = configs.filter { it.enabled }
  if (active.isEmpty()) return "No providers"
  return active.joinToString(", ") {
    when (it.kind) {
      com.eight87.tonearmboy.data.albumart.ProviderKind.YouTube -> "YouTube"
      com.eight87.tonearmboy.data.albumart.ProviderKind.ITunes -> "iTunes"
      com.eight87.tonearmboy.data.albumart.ProviderKind.MusicBrainz -> "MusicBrainz"
    }
  }
}

/**
 * Slider dialog for the MusicBrainz match-score threshold. 0–100 in
 * 1-point increments. The page's row already shows the current value
 * inline; the dialog shows the live value as the user drags + a
 * "Reset to 70 (default)" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicBrainzMatchScoreDialog(
  currentScore: Int,
  onConfirm: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  var value by remember(currentScore) { mutableStateOf(currentScore.toFloat()) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("MusicBrainz match threshold") },
    text = {
      Column(modifier = Modifier.semantics { testTag = "mb_match_score_dialog" }) {
        Text(
          text = "${value.toInt()}",
          style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
        )
        Text(
          text = "0 = accept anything, 100 = perfect match only. Default 70 — strict enough to reject obvious noise, loose enough to accept tag-vs-canonical drift.",
          style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 4.dp),
        )
        Slider(
          value = value,
          onValueChange = { value = it },
          valueRange = 0f..100f,
          steps = 99, // 100 distinct stops
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .semantics { testTag = "mb_match_score_slider" },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
          TextButton(onClick = { value = 70f }) {
            Text("Reset to 70")
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(value.toInt()) }) {
        Text(stringResource(R.string.settings_dialog_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.settings_dialog_cancel))
      }
    },
  )
}

// =============================================================================
// Generic radio-list picker dialog used across sub-pages.
// =============================================================================

@Composable
private fun <T> RadioPicker(
  title: String,
  options: Iterable<T>,
  label: (T) -> String,
  current: T,
  onPick: (T) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        options.forEach { option ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .selectable(selected = option == current, onClick = { onPick(option) })
              .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = option == current, onClick = null)
            Text(label(option), modifier = Modifier.padding(start = 12.dp))
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.settings_dialog_close))
      }
    },
  )
}

// =============================================================================
// D.9c.1 — Generic multi-select dialog. Multiple options can be enabled
// simultaneously; tapping Save commits the new set, Cancel discards.
// =============================================================================

@Composable
private fun <T> MultiSelectPicker(
  title: String,
  options: Iterable<T>,
  label: (T) -> String,
  currentSelection: Set<T>,
  onSave: (Set<T>) -> Unit,
  onDismiss: () -> Unit,
) {
  // Local draft selection so the user can tick / untick without committing
  // until they tap Save. Cancel discards everything.
  var draft by remember(currentSelection) { mutableStateOf(currentSelection) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        options.forEach { option ->
          val checked = option in draft
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .selectable(
                selected = checked,
                onClick = {
                  draft = if (checked) draft - option else draft + option
                },
              )
              .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(label(option), modifier = Modifier.padding(start = 12.dp))
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(draft) }) {
        Text(stringResource(R.string.settings_dialog_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.settings_dialog_cancel))
      }
    },
  )
}
