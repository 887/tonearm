package com.eight87.tonearmboy.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eight87.tonearmboy.BuildConfig
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.ui.settings.catalog.SettingsCard
import com.eight87.tonearmboy.ui.settings.catalog.SettingsDimens
import com.eight87.tonearmboy.ui.settings.catalog.SettingsRow
import com.eight87.tonearmboy.ui.settings.catalog.SettingsRowDivider
import kotlinx.coroutines.launch

/**
 * D.16.4 — About sub-page. Renders inside the same M3 Expressive grouped
 * cards (`SettingsCard` / `SettingsRow`) used elsewhere so the chrome
 * lines up with every other settings surface.
 *
 * Layout:
 *   - "Build" card: app name, version + SHA, build date.
 *   - "Source" card: GitHub repo link (browser intent) + MIT license.
 *   - "Credits" card: visual + chrome references and tech stack.
 *
 * D.16.5 — the build-version row hosts a tap counter (state machine in
 * [EasterEggController]). After three quick taps the fullscreen fox
 * dialog appears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
  onBack: () -> Unit,
  onLicenses: () -> Unit,
  snackbarHostState: SnackbarHostState,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // D.16.5 — counter lives across recompositions; reused across
  // multiple reveals (counter resets internally on reveal).
  val easterEgg = remember { EasterEggController() }
  var foxVisible by remember { mutableStateOf(false) }

  val versionName = stringResource(
    R.string.settings_about_version_subtitle,
    BuildConfig.VERSION_NAME,
    BuildConfig.GIT_SHA,
  )
  val buildDate = stringResource(R.string.settings_about_build_date_subtitle, BuildConfig.BUILD_DATE)

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_about_title)) },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { testTag = "about_back" },
          ) {
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
        .semantics { testTag = "about_screen" },
      verticalArrangement = Arrangement.spacedBy(SettingsDimens.CardSpacing),
    ) {
      // App-icon header. Whisperboy's About uses the same shape with its own icon at
      // the top; tonearmboy puts the fox here. The full-size reveal still happens
      // behind the easter-egg (triple-tap on version) — the header is a smaller
      // teaser that signals "this app has a fox" without spoiling the easter-egg
      // payload (the reveal is a 70%-scrim fullscreen modal, much more dramatic).
      Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
      ) {
        Image(
          painter = painterResource(id = R.drawable.easter_egg_fox),
          contentDescription = null,
          modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.app_name),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Medium,
        )
      }

      // ---- Build card ----
      SettingsCard(
        title = stringResource(R.string.settings_about_card_build),
        modifier = Modifier.padding(horizontal = SettingsDimens.PagePadding),
      ) {
        SettingsRow(
          id = "about.app_name",
          icon = Icons.Outlined.Info,
          label = stringResource(R.string.settings_about_application_label),
          subtitle = stringResource(R.string.settings_about_application_subtitle),
          onClick = null,
        )
        SettingsRowDivider()
        // D.16.5 — easter-egg target. The whole row is clickable; the
        // controller decides what to surface based on tap count.
        val easterEggFirst = stringResource(R.string.settings_about_easter_egg_first)
        val easterEggSecond = stringResource(R.string.settings_about_easter_egg_second)
        SettingsRow(
          id = "about.version",
          icon = Icons.Outlined.Numbers,
          label = stringResource(R.string.settings_about_version_label),
          subtitle = versionName,
          onClick = {
            when (easterEgg.tap(System.currentTimeMillis())) {
              EasterEggController.Outcome.FirstPromptSnackbar -> scope.launch {
                snackbarHostState.showSnackbar(easterEggFirst)
              }
              EasterEggController.Outcome.SecondPromptSnackbar -> scope.launch {
                snackbarHostState.showSnackbar(easterEggSecond)
              }
              EasterEggController.Outcome.Reveal -> {
                foxVisible = true
              }
            }
          },
        )
        SettingsRowDivider()
        SettingsRow(
          id = "about.build_date",
          icon = Icons.Outlined.Schedule,
          label = stringResource(R.string.settings_about_build_date_label),
          subtitle = buildDate,
          onClick = null,
        )
      }

      // ---- Source card ----
      SettingsCard(
        title = stringResource(R.string.settings_about_card_source),
        modifier = Modifier.padding(horizontal = SettingsDimens.PagePadding),
      ) {
        SettingsRow(
          id = "about.github",
          icon = Icons.Outlined.Launch,
          label = stringResource(R.string.settings_about_github_label),
          subtitle = stringResource(R.string.settings_about_github_subtitle),
          onClick = {
            openExternalBrowser(context, GITHUB_URL)
          },
        )
        SettingsRowDivider()
        SettingsRow(
          id = "about.licenses",
          icon = Icons.Outlined.Article,
          label = stringResource(R.string.settings_about_licenses_label),
          subtitle = stringResource(R.string.settings_about_licenses_subtitle),
          onClick = onLicenses,
        )
        SettingsRowDivider()
        SettingsRow(
          id = "about.license",
          icon = Icons.Outlined.Article,
          label = stringResource(R.string.settings_about_license_label),
          subtitle = stringResource(R.string.settings_about_license_subtitle),
          onClick = { openExternalBrowser(context, LICENSE_URL) },
        )
      }

      // ---- Credits card ----
      // Clean-room implementation. Inspiration drawn from UI screenshots
      // of Auxio and Harmony Music only — NO code was copied. The
      // clean-room claim is reinforced by the fact that we run on a
      // wholly different tech stack from either: Auxio is Kotlin on the
      // pre-Compose Android View system (XML layouts), Harmony is
      // Flutter / Dart, tonearmboy is Kotlin + Jetpack Compose. Every
      // line in this app is original. Critical because Auxio is GPL-3.0
      // and tonearmboy is MIT; if any Auxio code were here, our license
      // would be tainted. It isn't.
      SettingsCard(
        title = stringResource(R.string.settings_about_card_credits),
        modifier = Modifier.padding(horizontal = SettingsDimens.PagePadding),
      ) {
        SettingsRow(
          id = "about.credits.cleanroom",
          icon = Icons.Outlined.Info,
          label = stringResource(R.string.settings_about_credits_cleanroom_label),
          subtitle = stringResource(R.string.settings_about_credits_cleanroom_subtitle),
          onClick = null,
        )
        SettingsRowDivider()
        SettingsRow(
          id = "about.credits.auxio",
          icon = Icons.Outlined.Favorite,
          label = stringResource(R.string.settings_about_credits_auxio_label),
          subtitle = stringResource(R.string.settings_about_credits_auxio_subtitle),
          onClick = { openExternalBrowser(context, AUXIO_URL) },
        )
        SettingsRowDivider()
        SettingsRow(
          id = "about.credits.harmony",
          icon = Icons.Outlined.Favorite,
          label = stringResource(R.string.settings_about_credits_harmony_label),
          subtitle = stringResource(R.string.settings_about_credits_harmony_subtitle),
          onClick = { openExternalBrowser(context, HARMONY_URL) },
        )
        SettingsRowDivider()
        SettingsRow(
          id = "about.credits.media3",
          icon = Icons.Outlined.Code,
          label = stringResource(R.string.settings_about_credits_media3_label),
          subtitle = stringResource(R.string.settings_about_credits_media3_subtitle),
          onClick = null,
        )
      }
    }
  }

  if (foxVisible) {
    EasterEggFoxDialog(onDismiss = { foxVisible = false })
  }
}

/**
 * D.16.5.3 — fullscreen modal for the fox reveal. Tap-outside or
 * back-press dismisses; a 70 % black scrim sits behind the image so the
 * fox stands out regardless of theme.
 */
@Composable
private fun EasterEggFoxDialog(onDismiss: () -> Unit) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
      usePlatformDefaultWidth = false,
    ),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.7f))
        .clickable(onClick = onDismiss)
        .semantics { testTag = "easter_egg_fox_scrim" },
      contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
      Image(
        painter = painterResource(id = R.drawable.easter_egg_fox),
        contentDescription = stringResource(R.string.settings_about_easter_egg_fox_cd),
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp)
          .semantics { testTag = "easter_egg_fox_image" },
      )
    }
  }
}

private const val GITHUB_URL = "https://github.com/887/tonearmboy"
private const val LICENSE_URL = "https://github.com/887/tonearmboy/blob/main/LICENSE"
private const val AUXIO_URL = "https://github.com/OxygenCobalt/Auxio"
private const val HARMONY_URL = "https://github.com/anandnet/Harmony-Music"

/**
 * Open a URL in the user's default external browser, NOT in any
 * embedded WebView / Chrome Custom Tab. The plain `Intent.ACTION_VIEW`
 * with no category was matching in-app browser components on some
 * devices (the user reported this on a real phone). Adding
 * `CATEGORY_BROWSABLE` plus `Browser.EXTRA_APPLICATION_ID` plus the
 * generic `URI` scheme forces the system to route through the
 * configured browser-launcher only.
 */
private fun openExternalBrowser(context: android.content.Context, url: String) {
  val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
    addCategory(Intent.CATEGORY_BROWSABLE)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    putExtra("com.android.browser.application_id", context.packageName)
  }
  runCatching { context.startActivity(intent) }
}
