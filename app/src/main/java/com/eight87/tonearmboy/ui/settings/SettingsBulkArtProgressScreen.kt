package com.eight87.tonearmboy.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.data.albumart.AlbumArtBulkProgress
import com.eight87.tonearmboy.data.albumart.AlbumArtBulkWorker
import com.eight87.tonearmboy.data.albumart.StageDiag
import androidx.compose.ui.text.font.FontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Round 2 / Ask A — live progress for [AlbumArtBulkWorker].
 *
 * Subscribes to [AlbumArtBulkProgress.log] for the running counts +
 * log tail. The user starts a run via "Start now"; the worker writes
 * a heartbeat (`Running`) on entry to every album and a terminal
 * outcome on exit, so the LazyColumn streams without polling. The log
 * persists across worker completion — the screen stays visible so the
 * user can scroll back through history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBulkArtProgressScreen(
  onBack: () -> Unit,
  snackbarHostState: SnackbarHostState,
) {
  val context = LocalContext.current
  val log by AlbumArtBulkProgress.log.collectAsState()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_bulk_art_progress_title)) },
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
        .semantics { testTag = "settings_bulk_art_progress" },
    ) {
      // Sticky header: progress bar + counts + action buttons. Stays
      // pinned above the scrollable log so the user always knows what
      // the worker is doing without scrolling back to the top.
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceContainerLow)
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val statusText = when {
          log.running -> stringResource(R.string.settings_bulk_art_progress_running)
          log.entries.isEmpty() -> stringResource(R.string.settings_bulk_art_progress_idle)
          else -> stringResource(R.string.settings_bulk_art_progress_done)
        }
        Text(statusText, style = MaterialTheme.typography.titleMedium)
        Text(
          text = stringResource(
            R.string.settings_bulk_art_progress_counts,
            log.processed,
            log.totalTracks,
            log.hits,
          ),
          style = MaterialTheme.typography.bodyMedium,
        )
        if (log.running && log.totalTracks > 0) {
          LinearProgressIndicator(
            progress = { (log.processed.toFloat() / log.totalTracks.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier
              .fillMaxWidth()
              .semantics { testTag = "bulk_art_progress_bar" },
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = {
              enqueueBulkArtWorker(context, replace = true)
            },
            enabled = !log.running,
            modifier = Modifier.semantics { testTag = "bulk_art_start_button" },
          ) {
            Text(stringResource(R.string.settings_bulk_art_progress_start))
          }
          OutlinedButton(
            onClick = {
              androidx.work.WorkManager.getInstance(context).cancelUniqueWork(
                AlbumArtBulkWorker.UNIQUE_WORK_NAME,
              )
            },
            enabled = log.running,
          ) {
            Text(stringResource(R.string.settings_bulk_art_progress_cancel))
          }
        }
      }

      if (log.entries.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          contentAlignment = Alignment.TopCenter,
        ) {
          Text(
            text = stringResource(R.string.settings_bulk_art_progress_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        val listState = rememberLazyListState()
        // Round 9 — log appends to the bottom now. Auto-scroll to the
        // last entry while the worker is running so the newest update
        // is visible without manual scrolling. When idle (worker
        // finished) we leave scroll position alone so the user can
        // scroll back through history.
        LaunchedEffect(log.entries.size, log.running) {
          if (log.running && log.entries.isNotEmpty()) {
            listState.animateScrollToItem(log.entries.size - 1)
          }
        }
        BoxWithConstraints(
          modifier = Modifier
            .fillMaxSize()
            .semantics { testTag = "bulk_art_log_list" },
        ) {
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp, end = 8.dp),
          ) {
            items(
              log.entries,
              key = { e -> e.trackId?.let { "t-$it" } ?: "x-${e.timestampMs}-${e.albumName}-${e.outcome}" },
            ) { entry ->
              BulkArtLogRow(entry)
            }
            item { Spacer(Modifier.height(24.dp)) }
          }
          // Round 9 — minimal scrollbar overlay. Thumb height is the
          // visible-portion ratio; offset tracks the first visible
          // item + its scroll fraction. Hidden when content fits.
          val density = androidx.compose.ui.platform.LocalDensity.current
          val trackHeightPx = with(density) { maxHeight.toPx() }
          val thumb by remember(listState, trackHeightPx) {
            derivedStateOf {
              val info = listState.layoutInfo
              val total = info.totalItemsCount
              val visible = info.visibleItemsInfo
              if (total == 0 || visible.isEmpty() || trackHeightPx <= 0f) {
                null
              } else {
                val first = visible.first()
                val avgItem = visible.sumOf { it.size } / visible.size.toFloat()
                val contentPx = avgItem * total
                if (contentPx <= trackHeightPx) {
                  null
                } else {
                  val ratio = trackHeightPx / contentPx
                  val thumbPx = (trackHeightPx * ratio).coerceAtLeast(24f)
                  val scrolled = first.index * avgItem - first.offset
                  val maxScroll = contentPx - trackHeightPx
                  val frac = (scrolled / maxScroll).coerceIn(0f, 1f)
                  val offsetPx = (trackHeightPx - thumbPx) * frac
                  with(density) { thumbPx.toDp() to offsetPx.toDp() }
                }
              }
            }
          }
          thumb?.let { (thumbHeight, thumbOffset) ->
            Box(
              modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = thumbOffset)
                .width(4.dp)
                .height(thumbHeight)
                .background(
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                  shape = RoundedCornerShape(2.dp),
                ),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun BulkArtLogRow(entry: AlbumArtBulkProgress.LogEntry) {
  val (icon, tint) = iconFor(entry.outcome)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = entry.outcome.name,
      tint = tint,
      modifier = Modifier.padding(top = 2.dp),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
      // Round 3 — title line names the song when the worker has one;
      // album/artist drops to the subtitle. Older entries (kill-switch
      // skip, no-providers skip) have no trackTitle and fall back to
      // the album label.
      val titleLine = entry.trackTitle?.takeIf { it.isNotBlank() }
        ?: buildString {
          append(entry.albumName)
          entry.albumArtist?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
        }
      Text(
        text = titleLine,
        style = MaterialTheme.typography.bodyMedium,
      )
      val noteParts = buildList {
        add(formatTimestamp(entry.timestampMs))
        // When trackTitle was the headline, fold album/artist into the
        // subtitle so the row still shows where the song lives.
        if (entry.trackTitle != null) {
          val ctx = listOfNotNull(
            entry.albumArtist?.takeIf { it.isNotBlank() },
            entry.albumName.takeIf { it.isNotBlank() },
          ).joinToString(" · ")
          if (ctx.isNotEmpty()) add(ctx)
        }
        entry.providerKind?.let { add(AlbumArtBulkWorker.labelFor(it)) }
        entry.note?.let { add(it) }
        // Round 5 — surface the YouTube video id when known so the
        // user can paste it into a browser to verify the chain picked
        // the right upload. Diagnostic gold for the "why didn't this
        // hit" cases.
        entry.videoId?.let { add("id: $it") }
      }
      Text(
        text = noteParts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // Round 6 / Fix C — per-stage diagnostic sub-block. Renders
      // monospace one-liners so a screenshot tells the full story
      // without having to push files off the device.
      if (entry.diags.isNotEmpty()) {
        for (diag in entry.diags) {
          Text(
            text = formatDiag(diag),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
          )
        }
      }
    }
  }
}

/**
 * Round 6 / Fix C — render a [StageDiag] as a single short line.
 * Length-capped via [String.take] on captured text so a long URL
 * doesn't blow up the row vertically.
 */
private fun formatDiag(diag: StageDiag): String = when (diag) {
  is StageDiag.Filename -> "filename: " + (diag.matched?.let { "id $it" } ?: "no ID")
  is StageDiag.CommentTag -> {
    val kb = diag.bytesScanned / 1024
    when {
      diag.matched != null ->
        "tag-scan: id ${diag.matched} (${kb}KB scanned)"
      diag.captured != null ->
        "tag-scan: captured \"${diag.captured}\" (${kb}KB scanned, no 11-char ID)"
      else ->
        "tag-scan: no URL in ${kb}KB"
    }
  }
  is StageDiag.PipedSearch -> {
    val q = if (diag.query.length > 60) diag.query.take(60) + "…" else diag.query
    val tail = when {
      diag.matchedId != null && diag.durationMismatchSec != null ->
        " → id ${diag.matchedId} (${signed(diag.durationMismatchSec)}s)"
      diag.matchedId != null -> " → id ${diag.matchedId}"
      else -> " → ${diag.results} results, no match"
    }
    "piped: \"$q\"$tail"
  }
}

private fun signed(n: Int): String = if (n >= 0) "+$n" else n.toString()

private fun iconFor(o: AlbumArtBulkProgress.Outcome): Pair<ImageVector, Color> = when (o) {
  AlbumArtBulkProgress.Outcome.Hit -> Icons.Outlined.CheckCircle to Color(0xFF4CAF50)
  AlbumArtBulkProgress.Outcome.Miss -> Icons.Outlined.SearchOff to Color(0xFF9E9E9E)
  // Round 5 — visually distinct from generic Miss: the YouTube chain
  // tried every stage and resolved no id, which is a stronger signal
  // than "chain returned nothing".
  AlbumArtBulkProgress.Outcome.NoIdResolved -> Icons.Outlined.SearchOff to Color(0xFF9E9E9E)
  AlbumArtBulkProgress.Outcome.Skipped -> Icons.Outlined.HighlightOff to Color(0xFFB0BEC5)
  AlbumArtBulkProgress.Outcome.Throttled -> Icons.Outlined.ErrorOutline to Color(0xFFFFB74D)
  AlbumArtBulkProgress.Outcome.Error -> Icons.Outlined.ErrorOutline to Color(0xFFE57373)
  AlbumArtBulkProgress.Outcome.Running -> Icons.Outlined.HourglassEmpty to Color(0xFF42A5F5)
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTimestamp(ms: Long): String = timeFmt.format(Date(ms))

/**
 * Enqueue the bulk-art worker. [replace] true mirrors the manual
 * "Start now" — bumps an in-flight run aside; [replace] false (the
 * auto-discover toggle path) honours an existing run.
 */
internal fun enqueueBulkArtWorker(context: android.content.Context, replace: Boolean) {
  val req = androidx.work.OneTimeWorkRequestBuilder<AlbumArtBulkWorker>()
    .setConstraints(
      androidx.work.Constraints.Builder()
        .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
        .build(),
    )
    .build()
  androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
    AlbumArtBulkWorker.UNIQUE_WORK_NAME,
    if (replace) androidx.work.ExistingWorkPolicy.REPLACE else androidx.work.ExistingWorkPolicy.KEEP,
    req,
  )
}
