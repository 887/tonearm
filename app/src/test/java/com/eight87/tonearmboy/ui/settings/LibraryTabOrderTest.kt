package com.eight87.tonearmboy.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round 12 regression coverage for [LibraryTabOrder].
 *
 * The bug being pinned: pre-Round-12, [LibraryTabOrder.fromStored]
 * re-merged hidden tabs back into the visible list, silently undoing
 * every "hide tab" toggle the user made. [LibraryTabOrder.toStored]
 * never emitted the documented `_hidden_` marker, so there was no
 * way to persist hidden state either. These tests pin the corrected
 * round-trip.
 */
class LibraryTabOrderTest {

  @Test
  fun null_storage_returns_default_order() {
    assertEquals(LibraryTab.DefaultOrder, LibraryTabOrder.fromStored(null))
    assertEquals(LibraryTab.DefaultOrder, LibraryTabOrder.fromStored(""))
  }

  @Test
  fun toStored_with_all_visible_omits_marker() {
    val all = LibraryTab.entries.toList()
    val encoded = LibraryTabOrder.toStored(all)
    assertEquals(false, encoded.contains(LibraryTabOrder.HIDDEN_MARKER))
    assertEquals(all, LibraryTabOrder.fromStored(encoded))
  }

  @Test
  fun toStored_with_hidden_tab_includes_marker_and_round_trips() {
    val visible = LibraryTab.entries.filter { it != LibraryTab.Genres }
    val encoded = LibraryTabOrder.toStored(visible)
    assertEquals(true, encoded.contains(LibraryTabOrder.HIDDEN_MARKER))
    assertEquals(true, encoded.contains(LibraryTab.Genres.name))
    // Round-trip: fromStored returns only the visible portion.
    assertEquals(visible, LibraryTabOrder.fromStored(encoded))
  }

  @Test
  fun fromStored_drops_hidden_tabs() {
    val raw = "Songs,Albums,Artists,${LibraryTabOrder.HIDDEN_MARKER},Genres,Playlists"
    val decoded = LibraryTabOrder.fromStored(raw)
    assertEquals(listOf(LibraryTab.Songs, LibraryTab.Albums, LibraryTab.Artists), decoded)
  }

  @Test
  fun fromStored_preserves_user_visible_order() {
    // Albums first, then Songs — user reordered.
    val raw = "Albums,Songs,${LibraryTabOrder.HIDDEN_MARKER},Genres,Artists,Playlists"
    val decoded = LibraryTabOrder.fromStored(raw)
    assertEquals(listOf(LibraryTab.Albums, LibraryTab.Songs), decoded)
  }

  @Test
  fun fromStored_appends_new_tabs_to_visible_for_discoverability() {
    // Simulate a stored value that only knows about Songs+Albums (as
    // if those were the only two tabs at write time). Today's enum
    // also has Artists/Genres/Playlists; they should appear at the
    // tail of the visible list so a user upgrade surfaces them.
    val raw = "Songs,Albums"
    val decoded = LibraryTabOrder.fromStored(raw)
    assertEquals(LibraryTab.entries.size, decoded.size)
    assertEquals(LibraryTab.Songs, decoded[0])
    assertEquals(LibraryTab.Albums, decoded[1])
  }

  @Test
  fun fromStored_tolerates_unknown_tokens() {
    val raw = "Songs,GhostTab,Albums,Artists,Playlists,${LibraryTabOrder.HIDDEN_MARKER},Genres"
    val decoded = LibraryTabOrder.fromStored(raw)
    // GhostTab dropped; the rest preserves the user's visible order.
    assertEquals(
      listOf(LibraryTab.Songs, LibraryTab.Albums, LibraryTab.Artists, LibraryTab.Playlists),
      decoded,
    )
  }
}
