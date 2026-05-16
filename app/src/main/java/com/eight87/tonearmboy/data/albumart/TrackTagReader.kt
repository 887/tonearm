package com.eight87.tonearmboy.data.albumart

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Round 4 / Phase I — read text metadata that might carry a YouTube
 * source URL from a local audio file.
 *
 * Abstracted as an interface so [YouTubeProvider] can unit-test its
 * filename → comment-tag → Piped chain on the JVM without invoking the
 * Android-only file IO.
 */
interface TrackTagReader {
  /**
   * Returns a blob of text (likely containing a YouTube URL) for the
   * file at [path], or null when the file isn't readable / nothing
   * URL-shaped is present. The caller regex-scans the blob for a
   * YouTube ID.
   *
   * Implementations MUST be safe to call from [Dispatchers.IO].
   */
  suspend fun readTextTags(path: String): String?
}

/**
 * Production implementation — a small **byte-level URL scanner** that
 * sweeps the head + tail of the file for ASCII / UTF-8 substrings
 * matching the YouTube URL shapes NewPipe writes into container
 * COMMENT / metadata tags.
 *
 * ### Why not `android.media.MediaMetadataRetriever`?
 *
 * Android's MMR does NOT expose the `COMMENT` tag — `METADATA_KEY_COMMENT`
 * is not defined in the public SDK (verified on API 36 `android.jar`).
 * NewPipe writes the source YouTube URL into the container `COMMENT`
 * tag specifically (ID3v2 `COMM` for MP3, Ogg Vorbis `COMMENT` for
 * Opus/Ogg/WebM, iTunes `\xa9cmt` atom for M4A). Without a third-party
 * tag library (the user vetoed LGPL `jaudiotagger`) we can't go via
 * MMR.
 *
 * ### Why scanning the raw bytes works
 *
 * The COMMENT tag value is stored as plain UTF-8 in all three container
 * families, with no compression or scrambling. A NewPipe URL like
 * `https://www.youtube.com/watch?v=dQw4w9WgXcQ` appears verbatim in
 * the file bytes. Locating it doesn't require parsing the tag
 * framing — a substring search for `youtube.com/` / `youtu.be/` in the
 * region where tag data lives is sufficient.
 *
 * ### Scan regions
 *
 * - **Head (first 256 KB)** — covers ID3v2 (always at byte 0), Ogg
 *   comment headers (second page, well within first 64 KB on every
 *   real file), and M4A files with `moov` at start.
 * - **Tail (last 128 KB)** — covers M4A files with `moov` at end
 *   ("MOOV at end" layout, common for streamed downloads).
 *
 * Audio frame data sits *between* head and tail and is not scanned, so
 * we don't false-positive on random byte sequences inside compressed
 * audio.
 */
class AndroidTrackTagReader(
  @Suppress("unused") private val context: Context,
) : TrackTagReader {
  override suspend fun readTextTags(path: String): String? = withContext(Dispatchers.IO) {
    val file = runCatching { File(path) }.getOrNull() ?: return@withContext null
    if (!file.exists() || !file.isFile) return@withContext null
    val length = file.length()
    if (length == 0L) return@withContext null

    runCatching {
      RandomAccessFile(file, "r").use { raf ->
        val head = readRegion(raf, 0L, minOf(length, HEAD_SCAN_BYTES.toLong()).toInt())
        val tailStart = (length - TAIL_SCAN_BYTES).coerceAtLeast(HEAD_SCAN_BYTES.toLong())
        val tail = if (length > HEAD_SCAN_BYTES + TAIL_SCAN_BYTES) {
          readRegion(raf, tailStart, TAIL_SCAN_BYTES)
        } else {
          ByteArray(0)
        }
        val headMatch = scanForYouTubeUrl(head)
        val tailMatch = if (headMatch == null) scanForYouTubeUrl(tail) else null
        headMatch ?: tailMatch
      }
    }.getOrNull()
  }

  private fun readRegion(raf: RandomAccessFile, offset: Long, size: Int): ByteArray {
    raf.seek(offset)
    val buf = ByteArray(size)
    var read = 0
    while (read < size) {
      val n = raf.read(buf, read, size - read)
      if (n <= 0) break
      read += n
    }
    return if (read == size) buf else buf.copyOf(read)
  }

  companion object {
    /**
     * Locate a YouTube URL anywhere in [bytes]. We scan for the small
     * set of marker substrings NewPipe uses, then walk forward through
     * the URL-safe-character window (`[A-Za-z0-9_\-/?=&.:%]`) to capture
     * the surrounding URL. Returns the captured URL plus ~16 bytes of
     * leading context, or null when no marker is found.
     *
     * The returned string is fed to [YouTubeCommentExtractor] which
     * applies the strict URL regex; this scanner is a *locator*, not a
     * validator.
     *
     * Exposed at companion scope so JVM unit tests can exercise it
     * without instantiating the surrounding Android class.
     */
    internal fun scanForYouTubeUrl(bytes: ByteArray): String? {
      for (marker in MARKERS) {
        val idx = indexOf(bytes, marker)
        if (idx < 0) continue
        val end = walkUrlEnd(bytes, idx + marker.size)
        val start = (idx - LEAD_CONTEXT_BYTES).coerceAtLeast(0)
        return String(bytes, start, end - start, Charsets.US_ASCII)
      }
      return null
    }

    /** Walk forward while bytes are URL-safe. Caps the capture at 200 bytes. */
    private fun walkUrlEnd(bytes: ByteArray, from: Int): Int {
      var i = from
      val cap = minOf(bytes.size, from + MAX_URL_BYTES)
      while (i < cap && isUrlChar(bytes[i])) i++
      return i
    }

    private fun isUrlChar(b: Byte): Boolean {
      val c = b.toInt() and 0xFF
      return (c in 'A'.code..'Z'.code) ||
        (c in 'a'.code..'z'.code) ||
        (c in '0'.code..'9'.code) ||
        c == '_'.code || c == '-'.code || c == '/'.code ||
        c == '?'.code || c == '='.code || c == '&'.code ||
        c == '.'.code || c == ':'.code || c == '%'.code ||
        c == '+'.code || c == '#'.code || c == '~'.code
    }

    /**
     * Plain byte-array `indexOf` (avoids constructing a String from the
     * full buffer, which would be wasteful for the head region).
     */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
      if (needle.isEmpty() || haystack.size < needle.size) return -1
      outer@ for (i in 0..(haystack.size - needle.size)) {
        for (j in needle.indices) {
          if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
      }
      return -1
    }

    private const val HEAD_SCAN_BYTES: Int = 256 * 1024
    private const val TAIL_SCAN_BYTES: Int = 128 * 1024
    private const val LEAD_CONTEXT_BYTES: Int = 16
    private const val MAX_URL_BYTES: Int = 200

    // ASCII byte markers for the URL forms NewPipe emits. We don't
    // search for `https://` alone because that would false-positive on
    // any embedded link; the discriminator is the YouTube host segment.
    private val MARKERS: List<ByteArray> = listOf(
      "youtube.com/watch".toByteArray(Charsets.US_ASCII),
      "youtu.be/".toByteArray(Charsets.US_ASCII),
      "youtube.com/embed/".toByteArray(Charsets.US_ASCII),
      "youtube.com/shorts/".toByteArray(Charsets.US_ASCII),
    )
  }
}
