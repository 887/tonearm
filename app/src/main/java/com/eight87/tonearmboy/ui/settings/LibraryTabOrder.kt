package com.eight87.tonearmboy.ui.settings

/**
 * R.B.6 — value type for the persisted library-tab order.
 *
 * Wraps `List<LibraryTab>` with the parse / encode pair the
 * `KEY_LIBRARY_TABS` setting uses. The list represents **visible**
 * tabs in user-chosen order; hidden tabs are persisted after a
 * `_hidden_` marker so the user's per-tab choice survives a restart.
 *
 * Storage format example (Genres + Playlists toggled off):
 *   `Songs,Albums,Artists,_hidden_,Genres,Playlists`
 *
 * Round 12 fix: pre-Round-12 [fromStored] re-merged hidden tabs back
 * into the visible list (under "no tab can disappear on upgrade"),
 * which silently undid every toggle-off the user made. [fromStored]
 * now returns *only* the visible part. New tabs introduced by future
 * releases default to visible (appended to the tail) so a user upgrade
 * still surfaces them.
 *
 * Lives outside `SettingsRepository` because the parsing rule is
 * UI-flavoured (which tabs are user-visible / hidden) and the
 * repository should not own that vocabulary.
 */
object LibraryTabOrder {
  /** Marker token in storage that separates visible from hidden tabs. */
  internal const val HIDDEN_MARKER: String = "_hidden_"

  /**
   * Parse the persisted library-tab order. Tolerates unknown tokens.
   * Returns the **visible** tabs in user order. Hidden tabs (anything
   * after the [HIDDEN_MARKER]) are dropped from the returned list.
   * New tabs introduced after this storage write default to visible
   * and are appended.
   */
  fun fromStored(raw: String?): List<LibraryTab> {
    if (raw.isNullOrBlank()) return LibraryTab.DefaultOrder
    val parts = raw.split(",")
    val hiddenIdx = parts.indexOf(HIDDEN_MARKER)
    val visibleTokens = if (hiddenIdx >= 0) parts.subList(0, hiddenIdx) else parts
    val hiddenTokens = if (hiddenIdx >= 0) parts.subList(hiddenIdx + 1, parts.size) else emptyList()
    val visible = visibleTokens.mapNotNull { tok ->
      LibraryTab.entries.firstOrNull { it.name == tok }
    }
    val hidden = hiddenTokens.mapNotNull { tok ->
      LibraryTab.entries.firstOrNull { it.name == tok }
    }
    val accounted = (visible + hidden).toSet()
    // New tabs (added in a later release) default to visible so the
    // user discovers them; we don't silently hide them.
    val newTabs = LibraryTab.entries.filter { it !in accounted }
    return visible + newTabs
  }

  /**
   * Encode the visible tab order to storage form. Hidden tabs (those
   * in [LibraryTab.entries] but absent from [value]) are appended
   * after the [HIDDEN_MARKER] so their hidden state round-trips.
   */
  fun toStored(value: List<LibraryTab>): String {
    val visible = value
    val hidden = LibraryTab.entries.filter { it !in value }
    return if (hidden.isEmpty()) {
      visible.joinToString(",") { it.name }
    } else {
      visible.joinToString(",") { it.name } + "," + HIDDEN_MARKER + "," +
        hidden.joinToString(",") { it.name }
    }
  }
}
