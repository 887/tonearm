package com.eight87.tonearmboy.data.albumart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round 4 / Phase I — exercises the COMMENT-tag regex with realistic
 * NewPipe shapes plus adversarial false-positive bait.
 */
class YouTubeCommentExtractorTest {

  @Test
  fun `extracts id from full youtube watch url`() {
    assertEquals(
      "dQw4w9WgXcQ",
      YouTubeCommentExtractor.fromCommentText("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
    )
  }

  @Test
  fun `extracts id from youtu_be short url`() {
    assertEquals(
      "9bZkp7q19f0",
      YouTubeCommentExtractor.fromCommentText("youtu.be/9bZkp7q19f0"),
    )
  }

  @Test
  fun `extracts id from embed url with trailing NewPipe signature`() {
    assertEquals(
      "JGwWNGJdvx8",
      YouTubeCommentExtractor.fromCommentText("https://youtube.com/embed/JGwWNGJdvx8 | NewPipe 0.27.5"),
    )
  }

  @Test
  fun `extracts id from shorts url`() {
    assertEquals(
      "abcDEFghi12",
      YouTubeCommentExtractor.fromCommentText("https://www.youtube.com/shorts/abcDEFghi12"),
    )
  }

  @Test
  fun `extracts id from music_youtube_com domain`() {
    assertEquals(
      "dQw4w9WgXcQ",
      YouTubeCommentExtractor.fromCommentText("https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"),
    )
  }

  @Test
  fun `url-context match wins over bare token elsewhere in string`() {
    // The bare token "AAAAAAAAAAA" appears earlier, but the URL-context
    // match for `dQw4w9WgXcQ` should win because it's higher confidence.
    val text = "ref:AAAAAAAAAAA https://youtu.be/dQw4w9WgXcQ"
    assertEquals("dQw4w9WgXcQ", YouTubeCommentExtractor.fromCommentText(text))
  }

  @Test
  fun `bare 11-char token matched when no url present`() {
    assertEquals(
      "abcDEFghi12",
      YouTubeCommentExtractor.fromCommentText("yt-id abcDEFghi12 fwiw"),
    )
  }

  @Test
  fun `does not false-positive on token inside a longer base64-ish blob`() {
    // 11-char window inside a 30-char base64 blob is NOT a separate
    // token (no word boundary on either side).
    assertNull(YouTubeCommentExtractor.fromCommentText("checksum=ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"))
  }

  @Test
  fun `returns null on empty or null input`() {
    assertNull(YouTubeCommentExtractor.fromCommentText(null))
    assertNull(YouTubeCommentExtractor.fromCommentText(""))
    assertNull(YouTubeCommentExtractor.fromCommentText("   "))
  }

  @Test
  fun `returns null on free-text without any youtube reference`() {
    assertNull(YouTubeCommentExtractor.fromCommentText("Recorded live at the Royal Albert Hall, 1971."))
  }

  @Test
  fun `extracts from watch url with extra query params before v`() {
    assertEquals(
      "dQw4w9WgXcQ",
      YouTubeCommentExtractor.fromCommentText("https://www.youtube.com/watch?si=xyz&v=dQw4w9WgXcQ"),
    )
  }

  @Test
  fun `id with underscores and dashes is accepted`() {
    assertEquals(
      "a-b_c-d_e12",
      YouTubeCommentExtractor.fromCommentText("https://youtu.be/a-b_c-d_e12"),
    )
  }
}
