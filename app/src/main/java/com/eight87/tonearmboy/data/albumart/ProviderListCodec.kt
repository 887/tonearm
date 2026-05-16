package com.eight87.tonearmboy.data.albumart

/**
 * Cover-art Phase C — `(kind, enabled)` pair persisted as a list and
 * encoded into a single `String` for DataStore.
 *
 * The user reorders this list in Settings → Content → Cover art
 * providers; [ProviderChain] then walks active entries in priority
 * order.
 */
data class ProviderConfig(
  val kind: ProviderKind,
  val enabled: Boolean,
)

/**
 * Cover-art Phase C — round-trip codec for `List<ProviderConfig>`.
 *
 * Wire format: comma-separated `<Kind>:<on|off>` pairs in priority
 * order. Example: `"YouTube:on,ITunes:on,MusicBrainz:off"`.
 *
 * Canonicalisation rules — applied on every decode:
 *   - Unknown / malformed tokens are dropped silently (DataStore is
 *     never authoritative for the schema; trust the enum).
 *   - Every [ProviderKind] appears EXACTLY once — kinds missing from
 *     the stored string are appended in [ProviderKind.entries] order
 *     with `enabled = false`. Adding a new variant in a future
 *     release thus never surprises the user by being enabled.
 *   - Duplicate kinds collapse to the first occurrence (preserves the
 *     user's priority position).
 */
object ProviderListCodec {

  fun encode(configs: List<ProviderConfig>): String =
    configs.joinToString(",") { "${it.kind.name}:${if (it.enabled) "on" else "off"}" }

  fun decode(raw: String?): List<ProviderConfig> {
    val parsed = mutableListOf<ProviderConfig>()
    val seen = mutableSetOf<ProviderKind>()
    if (!raw.isNullOrBlank()) {
      for (token in raw.split(',')) {
        val parts = token.trim().split(':', limit = 2)
        if (parts.size != 2) continue
        val kind = runCatching { ProviderKind.valueOf(parts[0]) }.getOrNull() ?: continue
        if (kind in seen) continue
        val enabled = when (parts[1].trim().lowercase()) {
          "on", "true", "1" -> true
          "off", "false", "0" -> false
          else -> continue
        }
        parsed += ProviderConfig(kind, enabled)
        seen += kind
      }
    }
    // Canonical fill — every kind appears exactly once. Missing kinds
    // are appended OFF in declaration order.
    for (kind in ProviderKind.entries) {
      if (kind !in seen) parsed += ProviderConfig(kind, enabled = false)
    }
    return parsed
  }

  /**
   * Canonical default for a fresh install — every provider enabled,
   * YouTube first. The user's directive ("get me album art that works
   * for my YouTube downloaded music") drives this choice; the privacy
   * kill switch (`KEY_COVER_ART_DISABLED`) is the inert mode.
   */
  val DEFAULT: List<ProviderConfig> = listOf(
    ProviderConfig(ProviderKind.YouTube, enabled = true),
    ProviderConfig(ProviderKind.ITunes, enabled = true),
    ProviderConfig(ProviderKind.MusicBrainz, enabled = true),
  )
}
