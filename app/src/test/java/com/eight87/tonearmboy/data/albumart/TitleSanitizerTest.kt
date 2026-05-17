package com.eight87.tonearmboy.data.albumart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round 6 / Fix A — sanity coverage for the title / artist cleaner
 * that's now in front of every Piped search.
 */
class TitleSanitizerTest {

  @Test
  fun `coerceArtist drops the MediaStore unknown placeholder`() {
    assertNull(TitleSanitizer.coerceArtist("<unknown>"))
    assertNull(TitleSanitizer.coerceArtist("<UNKNOWN>"))
    assertNull(TitleSanitizer.coerceArtist("  <unknown>  "))
    assertNull(TitleSanitizer.coerceArtist(null))
    assertNull(TitleSanitizer.coerceArtist(""))
    assertNull(TitleSanitizer.coerceArtist("   "))
  }

  @Test
  fun `coerceArtist keeps a real artist`() {
    assertEquals("Boris Brejcha", TitleSanitizer.coerceArtist("Boris Brejcha"))
    assertEquals("RTTWLR", TitleSanitizer.coerceArtist("  RTTWLR  "))
  }

  @Test
  fun `cleanTitle strips emojis on a 639 Hz title`() {
    val raw = "639 Hz Manifest Love & Miracles ❤️ Positive Energy Healing Heart Chakra Frequency Meditation Music"
    val clean = TitleSanitizer.cleanTitle(raw)
    // ❤️ disappears, & is preserved, single spaces.
    assertEquals(
      "639 Hz Manifest Love & Miracles Positive Energy Healing Heart Chakra Frequency Meditation Music",
      clean,
    )
  }

  @Test
  fun `cleanTitle drops Mixed by qualifier and underscore-to-space`() {
    val raw = "Boris Brejcha - Droplex - Hozho style _ Minimal Techno ♦ I Am The One Who Knocks! (Mixed by EJ)"
    val clean = TitleSanitizer.cleanTitle(raw)
    // ♦ (OTHER_SYMBOL) gone, _ → space, (Mixed by EJ) gone, collapsed
    // whitespace.
    assertEquals(
      "Boris Brejcha - Droplex - Hozho style Minimal Techno I Am The One Who Knocks!",
      clean,
    )
  }

  @Test
  fun `cleanTitle drops Official Music Video qualifier`() {
    assertEquals(
      "Some Song",
      TitleSanitizer.cleanTitle("Some Song (Official Music Video)"),
    )
    assertEquals(
      "Some Song",
      TitleSanitizer.cleanTitle("Some Song [Lyrics]"),
    )
    assertEquals(
      "Some Song",
      TitleSanitizer.cleanTitle("Some Song (Extended Mix)"),
    )
  }

  @Test
  fun `buildQuery uses title only when artist is unknown`() {
    assertEquals(
      "Some Title",
      TitleSanitizer.buildQuery("<unknown>", "Some Title"),
    )
  }

  @Test
  fun `buildQuery combines real artist and clean title`() {
    assertEquals(
      "Boris Brejcha Some Title",
      TitleSanitizer.buildQuery("Boris Brejcha", "Some Title (Official Music Video)"),
    )
  }
}
