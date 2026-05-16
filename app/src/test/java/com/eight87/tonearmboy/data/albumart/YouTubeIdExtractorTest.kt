package com.eight87.tonearmboy.data.albumart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeIdExtractorTest {

  @Test
  fun `extracts NewPipe canonical id from end of basename`() {
    assertEquals(
      "dQw4w9WgXcQ",
      YouTubeIdExtractor.fromFilename("Rick Astley - Never Gonna Give You Up-dQw4w9WgXcQ.m4a"),
    )
  }

  @Test
  fun `extracts when path has directories`() {
    assertEquals(
      "abcDEF12345",
      YouTubeIdExtractor.fromFilename("/sdcard/Music/Some Album/Song-abcDEF12345.opus"),
    )
  }

  @Test
  fun `picks the last 11-char window when title has many dashes`() {
    // Title segment contains multiple `-<11 chars>` windows; the last
    // one before the extension is the YouTube ID slot.
    assertEquals(
      "ZZZZZZZZZZZ",
      YouTubeIdExtractor.fromFilename("art-AAAAAAAAAAA-foo-ZZZZZZZZZZZ.mp3"),
    )
  }

  @Test
  fun `underscore and dash inside id are allowed`() {
    assertEquals(
      "_-abc-_DEFG",
      YouTubeIdExtractor.fromFilename("song-_-abc-_DEFG.mp3"),
    )
  }

  @Test
  fun `returns null when id segment is wrong length`() {
    assertNull(YouTubeIdExtractor.fromFilename("song-tooshort.mp3"))
    assertNull(YouTubeIdExtractor.fromFilename("song-twelvecharss.mp3"))
  }

  @Test
  fun `returns null without an extension`() {
    assertNull(YouTubeIdExtractor.fromFilename("song-dQw4w9WgXcQ"))
  }

  @Test
  fun `returns null when no dash before the 11-char window`() {
    assertNull(YouTubeIdExtractor.fromFilename("dQw4w9WgXcQ.mp3"))
  }

  @Test
  fun `returns null on empty string`() {
    assertNull(YouTubeIdExtractor.fromFilename(""))
  }
}
