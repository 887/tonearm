package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Round 2 / Ask A — process-scoped progress sink for [AlbumArtBulkWorker].
 *
 * The worker writes a [LogEntry] every time it starts and finishes
 * processing an album, plus a summary header (total / processed / hits).
 * The Settings progress sub-page subscribes to [log] and renders the
 * live tail.
 *
 * Entries are capped at [MAX_ENTRIES] (most-recent kept) so a 5000-album
 * library can't grow the in-memory log without bound. Cleared explicitly
 * by [reset] when the user starts a new run from the UI.
 */
object AlbumArtBulkProgress {

  const val MAX_ENTRIES = 500

  /** Outcome of one album lookup attempt. */
  enum class Outcome { Hit, Miss, Skipped, Error, Running }

  /** One row in the progress log. */
  data class LogEntry(
    val timestampMs: Long,
    val albumName: String,
    val albumArtist: String?,
    /**
     * Which provider produced the [Outcome]. `null` when the entry
     * isn't tied to a single provider (e.g. a `Skipped` entry for an
     * album that already had art, or the chain-exhausted `Miss`).
     */
    val providerKind: ProviderKind?,
    val outcome: Outcome,
    /** Optional human-readable note (error message, "kill switch on", etc.). */
    val note: String? = null,
  )

  /** Full log + running counts, mirrored together as a single immutable snapshot. */
  data class BulkLog(
    val entries: List<LogEntry> = emptyList(),
    val totalAlbums: Int = 0,
    val processed: Int = 0,
    val hits: Int = 0,
    /** True while a worker is actively writing. */
    val running: Boolean = false,
  )

  private val _log = MutableStateFlow(BulkLog())
  val log: StateFlow<BulkLog> = _log.asStateFlow()

  /** Reset the log + counts. Called when the worker starts a fresh pass. */
  fun reset(totalAlbums: Int) {
    _log.value = BulkLog(totalAlbums = totalAlbums, running = true)
  }

  /** Mark the worker as finished. Keeps existing entries; flips `running`. */
  fun finish() {
    _log.update { it.copy(running = false) }
  }

  /**
   * Append a log entry; bumps [BulkLog.processed] for terminal outcomes
   * ([Outcome.Hit] / [Outcome.Miss] / [Outcome.Skipped] / [Outcome.Error]),
   * not for [Outcome.Running] heartbeats.
   */
  fun append(entry: LogEntry) {
    _log.update { current ->
      val nextEntries = (listOf(entry) + current.entries).take(MAX_ENTRIES)
      val terminal = entry.outcome != Outcome.Running
      val processed = if (terminal) current.processed + 1 else current.processed
      val hits = if (entry.outcome == Outcome.Hit) current.hits + 1 else current.hits
      current.copy(
        entries = nextEntries,
        processed = processed,
        hits = hits,
      )
    }
  }
}
