package com.eight87.tonearmboy.data.albumart

/**
 * Cover-art Phase B — YouTube video ID extraction.
 *
 * NewPipe writes downloaded files as `<title>-<id>.<ext>` where `<id>`
 * is the canonical 11-char YouTube ID (alphabet `[A-Za-z0-9_-]`). The
 * filename match is the single highest-signal source: zero network
 * calls and accurate for the user's dominant download path.
 *
 * Container-level extraction (Ogg `VORBIS_COMMENT`, M4A iTunes atoms,
 * ID3v2 `TXXX:youtube_video_id`) is intentionally NOT shipped here —
 * the plan gates the LGPL jaudiotagger dep on a Phase B.4 smoke-test
 * result. If filename extraction proves insufficient on the user's
 * real library, add the dep then.
 */
object YouTubeIdExtractor {
  /**
   * Matches `-<11-char-id>` immediately before the final extension.
   * The lookahead `(?=\.[^.]+$)` anchors the ID to a single-segment
   * extension so we don't false-match on names like
   * `something-abc.def.mp3` (which would otherwise pick `abc.def` to
   * match the 11-char window — but the lookahead requires `.<ext>$`).
   *
   * Edge cases the regex handles:
   *   - Multiple dashes anywhere in the title — `^.*-` is greedy so
   *     the captured group is the LAST 11-char window before `.ext`.
   *   - File extension required — bare basenames don't match (we have
   *     no way to know the boundary otherwise).
   *   - 11 chars exactly — the canonical YouTube ID width.
   *   - URL-safe Base64 alphabet — `[A-Za-z0-9_-]`.
   */
  private val PATTERN = Regex("""-([A-Za-z0-9_-]{11})(?=\.[^.]+$)""")

  /**
   * Returns the canonical YouTube video ID found at the tail of
   * [path]'s basename, or null when the filename doesn't carry one.
   * [path] may be a full filesystem path, content URI string, or bare
   * basename — only the segment after the last `/` is inspected.
   */
  fun fromFilename(path: String): String? {
    val basename = path.substringAfterLast('/').substringAfterLast('\\')
    // findAll → last → covers titles containing dashes; the LAST
    // 11-char window before `.ext` is the YouTube ID slot.
    return PATTERN.findAll(basename).lastOrNull()?.groupValues?.getOrNull(1)
  }
}
