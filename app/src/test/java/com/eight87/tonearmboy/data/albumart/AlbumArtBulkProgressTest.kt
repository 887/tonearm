package com.eight87.tonearmboy.data.albumart

import com.eight87.tonearmboy.data.albumart.AlbumArtBulkProgress.LogEntry
import com.eight87.tonearmboy.data.albumart.AlbumArtBulkProgress.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 9 — invariants for the [AlbumArtBulkProgress.append] state
 * machine: chronological in-place replace, single-bump processed /
 * hits accounting, MAX_ENTRIES ring buffer, and the "non-track
 * entries never bump processed" rule that fixed the Round 6 8/7
 * overcount bug.
 */
class AlbumArtBulkProgressTest {

  private fun entry(
    trackId: Long?,
    outcome: Outcome,
    title: String = "t$trackId",
    timestampMs: Long = 0L,
  ) = LogEntry(
    timestampMs = timestampMs,
    albumName = "alb",
    albumArtist = "art",
    trackTitle = title,
    providerKind = null,
    outcome = outcome,
    trackId = trackId,
  )

  @Before
  fun setUp() {
    // Singleton — isolate per test.
    AlbumArtBulkProgress.reset(0)
  }

  @Test
  fun `reset clears entries and flips running on`() {
    AlbumArtBulkProgress.append(entry(1L, Outcome.Running))
    AlbumArtBulkProgress.finish()

    AlbumArtBulkProgress.reset(totalTracks = 42)

    val log = AlbumArtBulkProgress.log.value
    assertTrue(log.entries.isEmpty())
    assertEquals(42, log.totalTracks)
    assertEquals(0, log.processed)
    assertEquals(0, log.hits)
    assertTrue(log.running)
  }

  @Test
  fun `new trackId appends at the end in chronological order`() {
    AlbumArtBulkProgress.reset(3)
    AlbumArtBulkProgress.append(entry(1L, Outcome.Running, title = "first"))
    AlbumArtBulkProgress.append(entry(2L, Outcome.Running, title = "second"))
    AlbumArtBulkProgress.append(entry(3L, Outcome.Running, title = "third"))

    val titles = AlbumArtBulkProgress.log.value.entries.map { it.trackTitle }
    assertEquals(listOf("first", "second", "third"), titles)
  }

  @Test
  fun `replay with same trackId replaces in place and bubbles to end`() {
    AlbumArtBulkProgress.reset(2)
    AlbumArtBulkProgress.append(entry(1L, Outcome.Running, title = "one-run"))
    AlbumArtBulkProgress.append(entry(2L, Outcome.Running, title = "two-run"))
    AlbumArtBulkProgress.append(entry(1L, Outcome.Hit, title = "one-hit"))

    val entries = AlbumArtBulkProgress.log.value.entries
    assertEquals(2, entries.size)
    // Track 2 bubbled before track 1 since track 1 was re-added last.
    assertEquals(2L, entries[0].trackId)
    assertEquals(1L, entries[1].trackId)
    assertEquals("one-hit", entries[1].trackTitle)
    assertEquals(Outcome.Hit, entries[1].outcome)
  }

  @Test
  fun `first terminal outcome bumps processed exactly once`() {
    AlbumArtBulkProgress.reset(1)
    AlbumArtBulkProgress.append(entry(1L, Outcome.Running))
    assertEquals(0, AlbumArtBulkProgress.log.value.processed)

    AlbumArtBulkProgress.append(entry(1L, Outcome.Miss))
    assertEquals(1, AlbumArtBulkProgress.log.value.processed)

    // Replays don't bump again.
    AlbumArtBulkProgress.append(entry(1L, Outcome.Hit))
    AlbumArtBulkProgress.append(entry(1L, Outcome.Error))
    assertEquals(1, AlbumArtBulkProgress.log.value.processed)
  }

  @Test
  fun `first terminal Hit bumps hits and later replays do not`() {
    AlbumArtBulkProgress.reset(1)
    AlbumArtBulkProgress.append(entry(1L, Outcome.Running))
    AlbumArtBulkProgress.append(entry(1L, Outcome.Hit))
    assertEquals(1, AlbumArtBulkProgress.log.value.hits)

    AlbumArtBulkProgress.append(entry(1L, Outcome.Miss))
    AlbumArtBulkProgress.append(entry(1L, Outcome.Hit))
    assertEquals(1, AlbumArtBulkProgress.log.value.hits)
  }

  @Test
  fun `first terminal Miss locks hits at zero even if later replay is Hit`() {
    AlbumArtBulkProgress.reset(1)
    AlbumArtBulkProgress.append(entry(1L, Outcome.Running))
    AlbumArtBulkProgress.append(entry(1L, Outcome.Miss))
    assertEquals(1, AlbumArtBulkProgress.log.value.processed)
    assertEquals(0, AlbumArtBulkProgress.log.value.hits)

    AlbumArtBulkProgress.append(entry(1L, Outcome.Hit))
    assertEquals(0, AlbumArtBulkProgress.log.value.hits)
  }

  @Test
  fun `MAX_ENTRIES caps the ring buffer to the last 500`() {
    AlbumArtBulkProgress.reset(600)
    val cap = AlbumArtBulkProgress.MAX_ENTRIES
    val n = cap + 50
    for (i in 1..n) {
      AlbumArtBulkProgress.append(entry(i.toLong(), Outcome.Running, title = "t$i"))
    }
    val entries = AlbumArtBulkProgress.log.value.entries
    assertEquals(cap, entries.size)
    // First retained entry is #51 (we dropped 50 oldest), last is #n.
    assertEquals((n - cap + 1).toLong(), entries.first().trackId)
    assertEquals(n.toLong(), entries.last().trackId)
  }

  @Test
  fun `non-track entries never bump processed and append at the end`() {
    AlbumArtBulkProgress.reset(7)
    // Seed 7 real tracks all completing as Hit → processed = 7, hits = 7.
    for (i in 1..7) {
      AlbumArtBulkProgress.append(entry(i.toLong(), Outcome.Running))
      AlbumArtBulkProgress.append(entry(i.toLong(), Outcome.Hit))
    }
    assertEquals(7, AlbumArtBulkProgress.log.value.processed)
    assertEquals(7, AlbumArtBulkProgress.log.value.hits)

    // Now spam non-track entries (trackId = null) — the Round 6 8/7
    // overcount bug would push processed past 7.
    repeat(5) {
      AlbumArtBulkProgress.append(entry(null, Outcome.Skipped, title = "kill-switch"))
    }
    val log = AlbumArtBulkProgress.log.value
    assertEquals(7, log.processed)
    assertEquals(7, log.hits)
    // Non-track entries appended at the end, never replaced.
    assertEquals(7 + 5, log.entries.size)
    assertEquals("kill-switch", log.entries.last().trackTitle)
  }

  @Test
  fun `finish flips running off without clearing entries`() {
    AlbumArtBulkProgress.reset(1)
    AlbumArtBulkProgress.append(entry(1L, Outcome.Hit))
    val before = AlbumArtBulkProgress.log.value.entries.size

    AlbumArtBulkProgress.finish()

    val log = AlbumArtBulkProgress.log.value
    assertFalse(log.running)
    assertEquals(before, log.entries.size)
  }
}
