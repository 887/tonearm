package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Round 4 / Phase I — Piped search duration filter.
 *
 * Covers:
 *  - ±2 s window picks the matching item over the unrelated top hit.
 *  - When no item is within tolerance, falls back to the top hit
 *    (no degradation vs Round 3 behaviour).
 *  - When expectedDurationSec is null, returns the top hit unchanged.
 */
class PipedClientDurationFilterTest {

  private fun shortClient() = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.SECONDS)
    .build()

  @Test
  fun `picks duration-matching item over unrelated top hit`() = runTest {
    val server = MockWebServer().apply {
      enqueue(MockResponse().setBody(
        """{"items":[
          {"url":"/watch?v=WRONGWRONG1","type":"stream","duration":62},
          {"url":"/watch?v=RIGHTRIGHT1","type":"stream","duration":213},
          {"url":"/watch?v=ALSOWRONG12","type":"stream","duration":420}
        ]}""",
      ))
      start()
    }
    val baseUrl = server.url("").toString().trimEnd('/')
    val piped = PipedClient(instances = listOf(baseUrl), client = shortClient())

    val id = piped.searchVideoId("artist", "title", expectedDurationSec = 212)

    assertEquals("RIGHTRIGHT1", id)
    server.shutdown()
  }

  @Test
  fun `accepts within plus-or-minus 2 seconds`() = runTest {
    val server = MockWebServer().apply {
      enqueue(MockResponse().setBody(
        """{"items":[
          {"url":"/watch?v=WRONGWRONG1","type":"stream","duration":100},
          {"url":"/watch?v=CLOSEENOUGH","type":"stream","duration":215}
        ]}""",
      ))
      start()
    }
    val baseUrl = server.url("").toString().trimEnd('/')
    val piped = PipedClient(instances = listOf(baseUrl), client = shortClient())

    val id = piped.searchVideoId("a", "t", expectedDurationSec = 213)

    assertEquals("CLOSEENOUGH", id)
    server.shutdown()
  }

  @Test
  fun `falls back to top hit when no item is within tolerance`() = runTest {
    val server = MockWebServer().apply {
      enqueue(MockResponse().setBody(
        """{"items":[
          {"url":"/watch?v=TOPHITTOP01","type":"stream","duration":60},
          {"url":"/watch?v=ALSOFAROFF1","type":"stream","duration":900}
        ]}""",
      ))
      start()
    }
    val baseUrl = server.url("").toString().trimEnd('/')
    val piped = PipedClient(instances = listOf(baseUrl), client = shortClient())

    val id = piped.searchVideoId("a", "t", expectedDurationSec = 213)

    assertEquals("TOPHITTOP01", id)
    server.shutdown()
  }

  @Test
  fun `null expected duration returns top hit unchanged`() = runTest {
    val server = MockWebServer().apply {
      enqueue(MockResponse().setBody(
        """{"items":[
          {"url":"/watch?v=TOPHITTOP01","type":"stream","duration":213},
          {"url":"/watch?v=SECONDPLACE","type":"stream","duration":214}
        ]}""",
      ))
      start()
    }
    val baseUrl = server.url("").toString().trimEnd('/')
    val piped = PipedClient(instances = listOf(baseUrl), client = shortClient())

    val id = piped.searchVideoId("a", "t", expectedDurationSec = null)

    assertEquals("TOPHITTOP01", id)
    server.shutdown()
  }
}
