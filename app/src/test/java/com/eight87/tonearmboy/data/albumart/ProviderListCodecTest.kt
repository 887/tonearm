package com.eight87.tonearmboy.data.albumart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderListCodecTest {

  @Test
  fun `encode round-trips through decode`() {
    val original = listOf(
      ProviderConfig(ProviderKind.YouTube, true),
      ProviderConfig(ProviderKind.ITunes, false),
      ProviderConfig(ProviderKind.MusicBrainz, true),
    )
    val raw = ProviderListCodec.encode(original)
    assertEquals("YouTube:on,ITunes:off,MusicBrainz:on", raw)
    assertEquals(original, ProviderListCodec.decode(raw))
  }

  @Test
  fun `decode appends missing kinds as OFF in canonical order`() {
    val decoded = ProviderListCodec.decode("MusicBrainz:on")
    assertEquals(
      listOf(
        ProviderConfig(ProviderKind.MusicBrainz, true),
        // Canonical fill order: YouTube then ITunes (the missing ones,
        // in ProviderKind.entries order minus MusicBrainz).
        ProviderConfig(ProviderKind.YouTube, false),
        ProviderConfig(ProviderKind.ITunes, false),
      ),
      decoded,
    )
  }

  @Test
  fun `decode of null returns canonical all-off list`() {
    val decoded = ProviderListCodec.decode(null)
    assertEquals(3, decoded.size)
    assertEquals(ProviderKind.entries.toSet(), decoded.map { it.kind }.toSet())
    assertTrue("every entry off", decoded.all { !it.enabled })
  }

  @Test
  fun `decode of blank returns canonical all-off list`() {
    val decoded = ProviderListCodec.decode("   ")
    assertEquals(3, decoded.size)
    assertTrue(decoded.all { !it.enabled })
  }

  @Test
  fun `malformed tokens are skipped`() {
    val decoded = ProviderListCodec.decode("garbage,YouTube:on,broken:value,MusicBrainz:wat,ITunes:1")
    assertEquals(
      listOf(
        ProviderConfig(ProviderKind.YouTube, true),
        ProviderConfig(ProviderKind.ITunes, true),
        // MusicBrainz appended OFF — its stored value was unparseable
        // and dropped.
        ProviderConfig(ProviderKind.MusicBrainz, false),
      ),
      decoded,
    )
  }

  @Test
  fun `duplicate kinds collapse to first occurrence`() {
    val decoded = ProviderListCodec.decode("YouTube:on,YouTube:off,ITunes:on,MusicBrainz:off")
    assertEquals(
      listOf(
        ProviderConfig(ProviderKind.YouTube, true),
        ProviderConfig(ProviderKind.ITunes, true),
        ProviderConfig(ProviderKind.MusicBrainz, false),
      ),
      decoded,
    )
  }

  @Test
  fun `DEFAULT enables every provider with YouTube first`() {
    assertEquals(
      listOf(
        ProviderConfig(ProviderKind.YouTube, true),
        ProviderConfig(ProviderKind.ITunes, true),
        ProviderConfig(ProviderKind.MusicBrainz, true),
      ),
      ProviderListCodec.DEFAULT,
    )
  }
}
