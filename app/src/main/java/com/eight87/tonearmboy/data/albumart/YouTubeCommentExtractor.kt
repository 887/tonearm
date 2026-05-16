package com.eight87.tonearmboy.data.albumart

/**
 * Round 4 / Phase I — extract a YouTube video ID from a free-form
 * comment / metadata-tag string.
 *
 * NewPipe writes the source YouTube URL into the `COMMENT` tag of every
 * file it downloads (ID3v2 `COMM`, Ogg Vorbis `COMMENT`, M4A `\xa9cmt`).
 * This object turns that string into the canonical 11-char video ID
 * when one is present.
 *
 * Two passes, in order of confidence:
 *
 *  1. **URL-context match** — `youtube.com/watch?v=<id>`, `youtu.be/<id>`,
 *     `youtube.com/embed/<id>`, `youtube.com/shorts/<id>`. These almost
 *     never false-positive: the URL host + path constrains the alphabet
 *     window to the YouTube ID slot.
 *  2. **Bare-token fallback** — only if pass 1 returns nothing. A bare
 *     11-char `[A-Za-z0-9_-]` window surrounded by word-boundary
 *     characters. This can false-positive on random Base64 strings, so
 *     it's intentionally the *last* resort — and we still require
 *     boundary characters around the window (anchor at start/end of
 *     string or non-`[A-Za-z0-9_-]` neighbour).
 *
 * Container reading itself lives in [AndroidTrackTagReader] (Android
 * `MediaMetadataRetriever`). This object is pure-regex so it can be
 * exhaustively unit-tested with realistic NewPipe COMMENT shapes on
 * the JVM.
 */
object YouTubeCommentExtractor {
  // youtube.com/watch?v=<id>(&…) — query string match.
  // youtu.be/<id>(?…|#…|/…|end) — short URL.
  // youtube.com/embed/<id>     — embed.
  // youtube.com/shorts/<id>    — shorts.
  // Allow optional scheme + www / m / music subdomains.
  private val URL_PATTERN = Regex(
    """(?:https?://)?(?:www\.|m\.|music\.)?youtube\.com/(?:watch\?(?:[^ &]*&)*v=|embed/|shorts/)([A-Za-z0-9_-]{11})""" +
      """|(?:https?://)?youtu\.be/([A-Za-z0-9_-]{11})""",
  )

  // Bare 11-char window, anchored at boundaries (start / end of string
  // or a non-ID character). Lookarounds avoid consuming neighbours.
  private val BARE_PATTERN = Regex("""(?<![A-Za-z0-9_-])([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")

  /**
   * Returns the first YouTube video ID found in [text], or null when
   * none is present. Empty / null input returns null.
   *
   * Resolution order:
   *  - URL-context match wins if any is present anywhere in the string.
   *  - If no URL-context match, falls back to the first bare 11-char
   *    boundary-anchored token.
   */
  fun fromCommentText(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val urlMatch = URL_PATTERN.find(text)
    if (urlMatch != null) {
      // groupValues[1] = youtube.com path, groupValues[2] = youtu.be path.
      val a = urlMatch.groupValues.getOrNull(1).orEmpty()
      val b = urlMatch.groupValues.getOrNull(2).orEmpty()
      val id = a.ifEmpty { b }
      if (id.isNotEmpty()) return id
    }
    return BARE_PATTERN.find(text)?.groupValues?.getOrNull(1)
  }
}
