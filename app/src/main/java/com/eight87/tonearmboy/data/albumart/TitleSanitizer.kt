package com.eight87.tonearmboy.data.albumart

/**
 * Round 6 / Fix A — clean up MediaStore-derived artist + title before
 * sending them to Piped search.
 *
 * NewPipe-downloaded YouTube tracks frequently lack ID3 artist tags,
 * which makes MediaStore return the literal placeholder string
 * `"<unknown>"` (see [android.provider.MediaStore.UNKNOWN_STRING]).
 * Forwarding that placeholder straight into a search query turns
 * `"<unknown> Boris Brejcha @ Art of…"` into a guaranteed zero-result
 * search. [coerceArtist] returns null for the placeholder.
 *
 * Titles often carry emojis, NewPipe's `/` → `_` substitution, and
 * `(Official Music Video)` / `(Mixed by EJ)` parenthesised qualifiers
 * that hurt precision. [cleanTitle] strips them.
 *
 * Both helpers are pure / deterministic and unit-tested in
 * `TitleSanitizerTest`.
 */
internal object TitleSanitizer {

  /**
   * Returns [artist] when it looks like a real artist name, or null
   * when it's blank or equals the MediaStore `<unknown>` placeholder
   * (case-insensitive).
   */
  fun coerceArtist(artist: String?): String? {
    if (artist.isNullOrBlank()) return null
    val trimmed = artist.trim()
    if (trimmed.equals("<unknown>", ignoreCase = true)) return null
    return trimmed
  }

  /**
   * Strip emojis, replace `_` runs with spaces (NewPipe filename-safe
   * substitution for `/`), drop known noise-qualifiers in parens
   * (`(Official Music Video)`, `(Mixed by EJ)`, …), collapse
   * whitespace, trim.
   */
  fun cleanTitle(title: String): String {
    var s = title

    // Strip noise-qualifier parens / brackets first (before emoji
    // strip — keeps the regex anchors readable).
    s = NOISE_QUALIFIER_REGEX.replace(s, " ")

    // Replace runs of underscores with a single space (NewPipe writes
    // `_` for `/` in filenames; titles inherit this).
    s = s.replace(UNDERSCORE_RUN_REGEX, " ")

    // Strip emoji / symbol-other / decorative codepoints. Keeps
    // letters / numbers / standard punctuation / whitespace. The
    // explicit `keep` list whitelists a handful of ASCII symbols
    // (MATH_SYMBOL by Unicode bucket) that are meaningful in titles.
    s = s.filter { ch ->
      if (ch in KEEP_CHARS) return@filter true
      val type = Character.getType(ch).toByte()
      type != Character.OTHER_SYMBOL &&
        type != Character.SURROGATE &&
        type != Character.PRIVATE_USE &&
        type != Character.MODIFIER_SYMBOL &&
        type != Character.FORMAT &&
        type != Character.NON_SPACING_MARK
    }

    // Collapse whitespace + trim.
    s = s.replace(WHITESPACE_RUN_REGEX, " ").trim()
    return s
  }

  /**
   * Build the final query string from a (possibly null) artist + a
   * raw title. Drops the artist when null/blank/`<unknown>` and runs
   * the title through [cleanTitle].
   */
  fun buildQuery(artist: String?, title: String): String {
    val cleanArtist = coerceArtist(artist)
    val cleanTitle = cleanTitle(title)
    return if (cleanArtist != null) "$cleanArtist $cleanTitle".trim() else cleanTitle
  }

  // Matches `(official...)`, `(HD)`, `[Lyrics]`, `(prod. by X)`,
  // `(Mixed by EJ)`, `(Extended Mix)`, `(Remastered 2020)`,
  // `(Audio)`, `(Video)` etc. Case-insensitive.
  private val NOISE_QUALIFIER_REGEX = Regex(
    "[\\(\\[]\\s*(?:official[^)\\]]*|hd|hq|4k|lyrics?[^)\\]]*|prod\\.[^)\\]]*|mixed[^)\\]]*|extended[^)\\]]*|remaster(?:ed)?[^)\\]]*|audio|video|music\\s*video|visualizer)\\s*[\\)\\]]",
    RegexOption.IGNORE_CASE,
  )
  private val UNDERSCORE_RUN_REGEX = Regex("_+")
  private val WHITESPACE_RUN_REGEX = Regex("\\s+")
  private val KEEP_CHARS: Set<Char> = setOf('&', '+', '<', '>', '=')
}
