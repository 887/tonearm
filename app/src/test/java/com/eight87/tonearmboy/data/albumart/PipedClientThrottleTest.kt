package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Round 5 — exercise the [PipedClient] rate-limit politeness:
 *
 *   - Per-host minimum interval delays the second request to the same
 *     host but not the first.
 *   - HTTP 429 + 403 lift into [ThrottledException] so the chain can
 *     disable the provider for the rest of a bulk run.
 */
class PipedClientThrottleTest {

  private fun shortClient() = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.SECONDS)
    .build()

  @Test
  fun `per-host interval delays back-to-back requests`() = runTest {
    // Two real-clock requests should land at least `intervalMs` apart
    // when issued back-to-back against the same host. The first one
    // goes through immediately; the second waits.
    val server = MockWebServer().apply {
      enqueue(MockResponse().setBody("""{"items":[{"url":"/watch?v=AAAAAAAAAAA","type":"stream"}]}"""))
      enqueue(MockResponse().setBody("""{"items":[{"url":"/watch?v=BBBBBBBBBBB","type":"stream"}]}"""))
      start()
    }
    val intervalMs = 250L
    val piped = PipedClient(
      instances = listOf(server.url("").toString().trimEnd('/')),
      client = shortClient(),
      perHostMinIntervalMs = intervalMs,
    )

    // Wall-clock measurement (the `delay()` inside throttle() goes
    // through the real-time dispatcher when called from withContext
    // on Dispatchers.IO; that's the production scenario the bulk
    // worker exercises). First request lands immediately; second
    // must wait at least `intervalMs`.
    val startMs = System.currentTimeMillis()
    val first = piped.searchVideoId("a", "x")
    val midMs = System.currentTimeMillis()
    val second = piped.searchVideoId("a", "y")
    val endMs = System.currentTimeMillis()

    assertEquals("AAAAAAAAAAA", first)
    assertEquals("BBBBBBBBBBB", second)
    assertTrue(
      "second request should be paced (waited ${endMs - midMs} ms, expected >= $intervalMs)",
      endMs - midMs >= intervalMs - 50L, // 50 ms slack for clock granularity
    )
    server.shutdown()
  }

  @Test
  fun `429 lifts into ThrottledException`() = runTest {
    val server = MockWebServer().apply {
      enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
      start()
    }
    val piped = PipedClient(
      instances = listOf(server.url("").toString().trimEnd('/')),
      client = shortClient(),
      perHostMinIntervalMs = 0L,
    )

    try {
      piped.searchVideoId("a", "x")
      fail("expected ThrottledException")
    } catch (t: ThrottledException) {
      assertNotNull(t.message)
      assertTrue(t.message!!.contains("429"))
    }
    server.shutdown()
  }

  @Test
  fun `403 lifts into ThrottledException`() = runTest {
    val server = MockWebServer().apply {
      enqueue(MockResponse().setResponseCode(403))
      start()
    }
    val piped = PipedClient(
      instances = listOf(server.url("").toString().trimEnd('/')),
      client = shortClient(),
      perHostMinIntervalMs = 0L,
    )

    try {
      piped.searchVideoId("a", "x")
      fail("expected ThrottledException")
    } catch (t: ThrottledException) {
      assertTrue(t.message!!.contains("403"))
    }
    server.shutdown()
  }

  @Test
  fun `non-throttling 5xx still falls through to null`() = runTest {
    val server = MockWebServer().apply {
      enqueue(MockResponse().setResponseCode(500))
      start()
    }
    val piped = PipedClient(
      instances = listOf(server.url("").toString().trimEnd('/')),
      client = shortClient(),
      perHostMinIntervalMs = 0L,
    )

    assertNull(piped.searchVideoId("a", "x"))
    server.shutdown()
  }
}
