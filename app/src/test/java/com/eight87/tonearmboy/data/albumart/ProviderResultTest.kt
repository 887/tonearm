package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 5 — exercise the richer [ProviderResult] / [ResolutionSource]
 * shape and the chain's run-scoped throttled-set behaviour.
 */
class ProviderResultTest {

  private val req = CoverArtRequest(
    albumName = "test album",
    albumArtist = "test artist",
    sampleTrackPath = null,
  )

  private class FakeProvider(
    override val kind: ProviderKind,
    private val behaviour: suspend () -> ProviderResult?,
  ) : CoverArtProvider {
    var calls = 0
      private set
    override suspend fun findCover(req: CoverArtRequest): ProviderResult? {
      calls++
      return behaviour()
    }
  }

  @Test
  fun `chain carries source and videoId through`() = runTest {
    val provider = FakeProvider(ProviderKind.YouTube) {
      ProviderResult(
        kind = ProviderKind.YouTube,
        url = "https://i.ytimg.com/vi/abcDEFghi12/hqdefault.jpg",
        source = ResolutionSource.CommentTag,
        videoId = "abcDEFghi12",
      )
    }
    val chain = ProviderChain(listOf(provider))

    val result = chain.resolveRich(req)

    assertEquals(ProviderKind.YouTube, result?.kind)
    assertEquals(ResolutionSource.CommentTag, result?.source)
    assertEquals("abcDEFghi12", result?.videoId)
  }

  @Test
  fun `throttled provider is skipped on subsequent resolves`() = runTest {
    val throttled: MutableSet<ProviderKind> = mutableSetOf()
    val rateLimited = FakeProvider(ProviderKind.YouTube) {
      throw ThrottledException("simulated 429")
    }
    val fallback = FakeProvider(ProviderKind.ITunes) {
      ProviderResult(ProviderKind.ITunes, "https://example/iT.jpg", ResolutionSource.Direct)
    }
    val chain = ProviderChain(listOf(rateLimited, fallback))

    val first = chain.resolveRich(req, throttled)
    val second = chain.resolveRich(req, throttled)

    assertEquals(ProviderKind.ITunes, first?.kind)
    assertEquals(ProviderKind.ITunes, second?.kind)
    // YouTube was called once (then throttled), iTunes twice
    // (back-up for both resolves).
    assertEquals(1, rateLimited.calls)
    assertEquals(2, fallback.calls)
    assertTrue(ProviderKind.YouTube in throttled)
  }

  @Test
  fun `default findCover wraps legacy findCoverUrl in Direct`() = runTest {
    val legacy = object : CoverArtProvider {
      override val kind: ProviderKind = ProviderKind.ITunes
      override suspend fun findCoverUrl(req: CoverArtRequest): String? = "url"
    }
    val result = legacy.findCover(req)
    assertEquals(ResolutionSource.Direct, result?.source)
    assertEquals("url", result?.url)
    assertNull(result?.videoId)
  }

  @Test
  fun `chain resolveRich returns null when every provider misses`() = runTest {
    val chain = ProviderChain(
      listOf(
        FakeProvider(ProviderKind.YouTube) { null },
        FakeProvider(ProviderKind.ITunes) { null },
      ),
    )
    assertNull(chain.resolveRich(req))
  }
}
