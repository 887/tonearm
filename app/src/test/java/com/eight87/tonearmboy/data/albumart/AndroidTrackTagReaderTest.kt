package com.eight87.tonearmboy.data.albumart

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 4 / Phase I — byte-scanner unit tests for
 * [AndroidTrackTagReader.scanForYouTubeUrl] (companion-scoped, so
 * we don't need an Android `Context` to exercise the scan logic).
 */
class AndroidTrackTagReaderTest {

  private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

  @Test
  fun `finds youtube watch url anywhere in the buffer`() {
    val padded = bytes(" ".repeat(500)) +
      bytes("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1s") +
      bytes(" ".repeat(500))
    val captured = AndroidTrackTagReader.scanForYouTubeUrl(padded)
    assertNotNull(captured)
    assertTrue("captured: $captured", captured!!.contains("dQw4w9WgXcQ"))
  }

  @Test
  fun `finds youtu_be short url`() {
    val padded = bytes("metadata-blob ") + bytes("youtu.be/9bZkp7q19f0") + bytes(" trailer")
    val captured = AndroidTrackTagReader.scanForYouTubeUrl(padded)
    assertNotNull(captured)
    assertTrue(captured!!.contains("9bZkp7q19f0"))
  }

  @Test
  fun `finds embed url`() {
    val padded = bytes("COMM    eng") +
      bytes("https://youtube.com/embed/JGwWNGJdvx8")
    val captured = AndroidTrackTagReader.scanForYouTubeUrl(padded)
    assertNotNull(captured)
    assertTrue(captured!!.contains("JGwWNGJdvx8"))
  }

  @Test
  fun `returns null when no marker present`() {
    val padded = bytes("random tag data, no link here at all")
    assertNull(AndroidTrackTagReader.scanForYouTubeUrl(padded))
  }

  @Test
  fun `extractor pipeline finds id in scanner output`() {
    // Simulates an M4A '@cmt' atom with a NewPipe URL value following.
    val padded = bytes("?cmt") +
      bytes("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    val captured = AndroidTrackTagReader.scanForYouTubeUrl(padded)
    assertNotNull(captured)
    val id = YouTubeCommentExtractor.fromCommentText(captured)
    assertTrue("expected dQw4w9WgXcQ, captured=$captured, id=$id", id == "dQw4w9WgXcQ")
  }
}
