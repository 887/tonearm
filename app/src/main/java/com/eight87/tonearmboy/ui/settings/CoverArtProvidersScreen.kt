package com.eight87.tonearmboy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.data.albumart.PipedClient
import com.eight87.tonearmboy.data.albumart.ProviderConfig
import com.eight87.tonearmboy.data.albumart.ProviderKind
import com.eight87.tonearmboy.data.albumart.ProviderListCodec
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Cover-art Phase D.2 — drag-to-reorder provider chain editor.
 *
 * Three rows (one per [ProviderKind]), each with a drag-handle icon
 * (long-press or drag), a label / description body, and a trailing
 * enable toggle. The body is clickable when the provider has its own
 * sub-sub-page (YouTube → Piped instance editor).
 *
 * Persistence is immediate: every reorder / toggle writes the new
 * encoded list to DataStore via [LibrarySettings.coverArtProviders].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverArtProvidersScreen(
  library: LibrarySettings,
  onBack: () -> Unit,
  onOpenPipedInstances: () -> Unit,
  snackbarHostState: SnackbarHostState,
) {
  val configs by library.coverArtProviders.flow.collectAsState(
    initial = ProviderListCodec.DEFAULT,
  )
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  val reorderState = rememberReorderableLazyListState(listState) { from, to ->
    val newList = configs.toMutableList().apply {
      add(to.index, removeAt(from.index))
    }
    scope.launch { library.coverArtProviders.set(newList) }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_content_cover_art_providers_title)) },
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
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .semantics { testTag = "cover_art_providers_screen" },
    ) {
      Text(
        text = stringResource(R.string.settings_content_cover_art_providers_hint),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      )
      HorizontalDivider()
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
      ) {
        itemsIndexed(configs, key = { _, c -> c.kind.name }) { index, config ->
          ReorderableItem(reorderState, key = config.kind.name) { isDragging ->
            ProviderRow(
              config = config,
              draggable = Modifier,
              dragHandleModifier = Modifier.draggableHandle(),
              onToggle = { enabled ->
                val updated = configs.toMutableList().also {
                  it[index] = it[index].copy(enabled = enabled)
                }
                scope.launch { library.coverArtProviders.set(updated) }
              },
              onOpenBody = if (config.kind == ProviderKind.YouTube) onOpenPipedInstances else null,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ProviderRow(
  config: ProviderConfig,
  draggable: Modifier,
  dragHandleModifier: Modifier,
  onToggle: (Boolean) -> Unit,
  onOpenBody: (() -> Unit)?,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .then(draggable)
      .semantics { testTag = "provider_row_${config.kind.name}" },
    color = MaterialTheme.colorScheme.surface,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
      val reorderCd = "Reorder ${config.kind.name}"
      val dragTag = "provider_drag_${config.kind.name}"
      IconButton(
        onClick = {},
        modifier = dragHandleModifier
          .size(40.dp)
          .semantics {
            contentDescription = reorderCd
            testTag = dragTag
          },
      ) {
        Icon(Icons.Default.DragHandle, contentDescription = null)
      }
      Column(
        modifier = Modifier
          .weight(1f)
          .padding(vertical = 8.dp)
          .let { m -> if (onOpenBody != null) m else m },
      ) {
        Text(
          text = labelFor(config.kind),
          style = MaterialTheme.typography.titleSmall,
        )
        Text(
          text = descriptionFor(config.kind),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onOpenBody != null) {
          TextButton(
            onClick = onOpenBody,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
          ) {
            Text("Piped instances…")
          }
        }
      }
      Spacer(Modifier.width(8.dp))
      val toggleTag = "provider_toggle_${config.kind.name}"
      Switch(
        checked = config.enabled,
        onCheckedChange = onToggle,
        modifier = Modifier.semantics { testTag = toggleTag },
      )
    }
    HorizontalDivider()
  }
}

@Composable
private fun labelFor(kind: ProviderKind): String = stringResource(
  when (kind) {
    ProviderKind.YouTube -> R.string.settings_content_cover_art_provider_youtube
    ProviderKind.ITunes -> R.string.settings_content_cover_art_provider_itunes
    ProviderKind.MusicBrainz -> R.string.settings_content_cover_art_provider_musicbrainz
  },
)

@Composable
private fun descriptionFor(kind: ProviderKind): String = stringResource(
  when (kind) {
    ProviderKind.YouTube -> R.string.settings_content_cover_art_provider_youtube_desc
    ProviderKind.ITunes -> R.string.settings_content_cover_art_provider_itunes_desc
    ProviderKind.MusicBrainz -> R.string.settings_content_cover_art_provider_musicbrainz_desc
  },
)

/**
 * Cover-art Phase D.3 — Piped instance list editor.
 *
 * Free-form multi-line text field; the worker parses comma-separated
 * entries (whitespace trimmed). Empty / blank falls back to
 * [PipedClient.DEFAULT_PIPED_INSTANCES].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipedInstancesScreen(
  library: LibrarySettings,
  onBack: () -> Unit,
  snackbarHostState: SnackbarHostState,
) {
  val storedRaw by library.pipedInstances.flow.collectAsState(initial = "")
  val scope = rememberCoroutineScope()
  var draft by rememberSaveable { mutableStateOf(storedRaw) }
  // Re-seed the draft when DataStore loads asynchronously after first
  // composition with an empty value.
  LaunchedEffect(storedRaw) {
    if (draft.isEmpty() && storedRaw.isNotEmpty()) draft = storedRaw
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_content_cover_art_piped_instances_title)) },
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
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = stringResource(R.string.settings_content_cover_art_piped_instances_subtitle),
        style = MaterialTheme.typography.bodyMedium,
      )
      OutlinedTextField(
        value = draft,
        onValueChange = { value ->
          draft = value
          scope.launch { library.pipedInstances.set(value) }
        },
        modifier = Modifier
          .fillMaxWidth()
          .semantics { testTag = "piped_instances_field" },
        placeholder = {
          Text(PipedClient.DEFAULT_PIPED_INSTANCES.joinToString(",\n"))
        },
        singleLine = false,
        minLines = 4,
      )
      TextButton(
        onClick = {
          draft = ""
          scope.launch { library.pipedInstances.set("") }
        },
      ) {
        Text(stringResource(R.string.settings_content_cover_art_piped_instances_reset))
      }
    }
  }
}
