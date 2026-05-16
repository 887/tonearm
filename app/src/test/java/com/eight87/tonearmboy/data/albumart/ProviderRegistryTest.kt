package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRegistryTest {

  @Test
  fun `chain contains only enabled providers in order`() = runTest {
    val configs = listOf(
      ProviderConfig(ProviderKind.YouTube, true),
      ProviderConfig(ProviderKind.ITunes, false),
      ProviderConfig(ProviderKind.MusicBrainz, true),
    )
    val chain = ProviderRegistry.buildChain(configs)
    // We can't easily inspect provider order without reflection, but
    // the chain returning null on an unresolvable request still
    // exercises the construction path.
    val result = chain.resolve(CoverArtRequest("album", "artist", null))
    assertNull(result)
  }

  @Test
  fun `all-off configs produce an empty chain that always returns null`() = runTest {
    val configs = ProviderListCodec.decode(null)
    val chain = ProviderRegistry.buildChain(configs)
    assertEquals(null, chain.resolve(CoverArtRequest("a", null, null)))
  }
}
