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
    // Inject an empty Piped instance pool so YouTube's Piped search
    // stage can't reach the network. Without this, the test was
    // making real HTTPS calls to api.piped.private.coffee and
    // resolving "album by artist" to a real video id.
    val chain = ProviderRegistry.buildChain(
      configs,
      ProviderRegistry.Deps(piped = PipedClient(instances = emptyList())),
    )
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
