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

  /**
   * Outcome of one album lookup attempt.
   *
   * Round 5 — kept as an enum (not a sealed type) so the per-row
   * icon-tint lookup stays a single `when`. Sub-stage attribution
   * (filename / COMMENT tag / Piped search) and the YouTube video id
   * ride on [LogEntry.source] / [LogEntry.videoId] separately.
   *
   * - [Hit] — provider returned a URL and the cover was saved.
   * - [Miss] — chain exhausted, no provider matched (was "NoProviderHit").
   * - [NoIdResolved] — YouTube tried every stage, no id found.
   * - [Skipped] — kill switch / no providers / user pinned / throttled.
   * - [Throttled] — provider hit 429/403; disabled for rest of run.
   * - [Error] — service-level error (network, IO, timeout).
   * - [Running] — heartbeat as the worker enters this song.
   */
  enum class Outcome { Hit, Miss, NoIdResolved, Skipped, Throttled, Error, Running }

  /**
   * One row in the progress log.
   *
   * Round 3 — the worker walks tracks (not albums); [trackTitle] names
   * the in-flight song so the UI can render per-song progress. The
   * album fields stay populated for context (and for older album-level
   * skip / error entries fired by the kill-switch / no-providers
   * branches that pre-date the per-track walk).
   */
  data class LogEntry(
    val timestampMs: Long,
    val albumName: String,
    val albumArtist: String?,
    /** Round 3 — song title for the per-track walk; null for non-track entries. */
    val trackTitle: String? = null,
    /**
     * Which provider produced the [Outcome]. `null` when the entry
     * isn't tied to a single provider (e.g. a `Skipped` entry for an
     * album that already had art, or the chain-exhausted `Miss`).
     */
    val providerKind: ProviderKind?,
    val outcome: Outcome,
    /** Optional human-readable note (error message, "kill switch on", etc.). */
    val note: String? = null,
    /**
     * Round 5 — sub-stage that produced the hit ("filename" / "COMMENT
     * tag" / "Piped search" / "Direct"). Null for non-terminal /
     * non-hit entries. Surfaced in the log row as
     * "Saved from YouTube (filename)".
     */
    val source: ResolutionSource? = null,
    /**
     * Round 5 — YouTube video id when known, even for the
     * `IdFoundNoThumbnail` / `Hit` cases. Diagnostic gold when the
     * user wants to know exactly which video the chain picked.
     */
    val videoId: String? = null,
    /**
     * Round 6 / Fix B — stable per-track id so a Running entry can be
     * **replaced in place** by its Hit / Miss / etc. terminal update.
     * Null for non-track entries (kill-switch skip, no-providers skip
     * — those don't get coalesced).
     */
    val trackId: Long? = null,
    /**
     * Round 6 / Fix C — per-stage diagnostics collected by the
     * provider chain. Rendered as a small monospace sub-block under
     * the row so a screenshot shows the full story without the user
     * pulling a file off the device.
     */
    val diags: List<StageDiag> = emptyList(),
  )

  /**
   * Full log + running counts, mirrored together as a single immutable
   * snapshot.
   *
   * Round 3 — `totalTracks` / `processed` now count songs, not albums.
   * The field name is the user-facing unit; the Settings progress
   * screen renders "Songs processed: X / Y" off these counts.
   */
  data class BulkLog(
    val entries: List<LogEntry> = emptyList(),
    val totalTracks: Int = 0,
    val processed: Int = 0,
    val hits: Int = 0,
    /** True while a worker is actively writing. */
    val running: Boolean = false,
  )

  private val _log = MutableStateFlow(BulkLog())
  val log: StateFlow<BulkLog> = _log.asStateFlow()

  /** Reset the log + counts. Called when the worker starts a fresh pass. */
  fun reset(totalTracks: Int) {
    _log.value = BulkLog(totalTracks = totalTracks, running = true)
  }

  /** Mark the worker as finished. Keeps existing entries; flips `running`. */
  fun finish() {
    _log.update { it.copy(running = false) }
  }

  /**
   * Append OR replace-in-place a log entry.
   *
   * Round 6 / Fix B — when [LogEntry.trackId] is non-null AND an
   * existing entry shares the same trackId, the existing entry is
   * **replaced** (and bubbled to the top) instead of appended. This
   * collapses the "Looking up… → Saved/Miss" two-row sequence into
   * a single row per track that mutates as the worker progresses.
   *
   * `processed` is bumped only when the row's terminal state lands
   * for the first time. `hits` is bumped only when the row's first
   * terminal state is a [Outcome.Hit].
   */
  fun append(entry: LogEntry) {
    _log.update { current ->
      val tid = entry.trackId
      val existingIdx = if (tid != null) current.entries.indexOfFirst { it.trackId == tid } else -1
      val existing = if (existingIdx >= 0) current.entries[existingIdx] else null
      val nextEntries = if (existingIdx >= 0) {
        // Round 9 — replace in place + bubble to **bottom** so the
        // log reads top-to-bottom in chronological order. The progress
        // screen auto-scrolls to the last item while running so the
        // newest update stays visible.
        val without = current.entries.toMutableList().also { it.removeAt(existingIdx) }
        (without + entry).takeLast(MAX_ENTRIES)
      } else {
        (current.entries + entry).takeLast(MAX_ENTRIES)
      }
      // Count a track as "processed" exactly once: the first time it
      // transitions to a terminal state. Non-track entries (Throttled
      // notices, kill-switch / no-providers skips — they carry
      // `trackId = null`) never bump `processed` so they can't push
      // the counter past `totalTracks`.
      val wasTerminal = existing != null && existing.outcome != Outcome.Running
      val nowTerminal = entry.outcome != Outcome.Running
      val newlyTerminal = nowTerminal && !wasTerminal && tid != null
      val processed = if (newlyTerminal) current.processed + 1 else current.processed
      // Count a hit exactly once per track (and only when the FIRST
      // terminal transition was a Hit — a later replay can't flip it).
      val hits = if (newlyTerminal && entry.outcome == Outcome.Hit) {
        current.hits + 1
      } else current.hits
      current.copy(
        entries = nextEntries,
        processed = processed,
        hits = hits,
      )
    }
  }
}
