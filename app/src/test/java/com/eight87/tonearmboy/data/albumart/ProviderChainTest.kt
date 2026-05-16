package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderChainTest {
  private val req = CoverArtRequest(
    albumName = "test album",
    albumArtist = "test artist",
    sampleTrackPath = null,
  )

  private class FakeProvider(
    override val kind: ProviderKind,
    private val behaviour: suspend () -> String?,
  ) : CoverArtProvider {
    var calls = 0
      private set
    override suspend fun findCoverUrl(req: CoverArtRequest): String? {
      calls++
      return behaviour()
    }
  }

  @Test
  fun `walks providers in order returning first hit`() = runTest {
    val first = FakeProvider(ProviderKind.YouTube) { "yt-url" }
    val second = FakeProvider(ProviderKind.ITunes) { "it-url" }
    val chain = ProviderChain(listOf(first, second))

    val result = chain.resolve(req)

    assertEquals(ProviderKind.YouTube to "yt-url", result)
    assertEquals(1, first.calls)
    assertEquals(0, second.calls)
  }

  @Test
  fun `skips providers that return null`() = runTest {
    val miss = FakeProvider(ProviderKind.YouTube) { null }
    val hit = FakeProvider(ProviderKind.ITunes) { "it-url" }
    val chain = ProviderChain(listOf(miss, hit))

    val result = chain.resolve(req)

    assertEquals(ProviderKind.ITunes to "it-url", result)
    assertEquals(1, miss.calls)
    assertEquals(1, hit.calls)
  }

  @Test
  fun `swallows provider exceptions and continues`() = runTest {
    val boom = FakeProvider(ProviderKind.YouTube) { error("network down") }
    val hit = FakeProvider(ProviderKind.MusicBrainz) { "mb-url" }
    val chain = ProviderChain(listOf(boom, hit))

    val result = chain.resolve(req)

    assertEquals(ProviderKind.MusicBrainz to "mb-url", result)
  }

  @Test
  fun `returns null when every provider misses`() = runTest {
    val chain = ProviderChain(listOf(
      FakeProvider(ProviderKind.YouTube) { null },
      FakeProvider(ProviderKind.ITunes) { null },
      FakeProvider(ProviderKind.MusicBrainz) { null },
    ))

    assertNull(chain.resolve(req))
  }

  @Test
  fun `empty chain returns null without firing anything`() = runTest {
    val chain = ProviderChain(emptyList())
    assertNull(chain.resolve(req))
  }
}
