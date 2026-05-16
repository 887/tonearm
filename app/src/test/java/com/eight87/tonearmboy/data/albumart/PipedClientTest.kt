package com.eight87.tonearmboy.data.albumart

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class PipedClientTest {

  private fun shortClient() = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.SECONDS)
    .build()

  @Test
  fun `parses videoId from top music_songs hit`() = runTest {
    val server = MockWebServer().apply {
      enqueue(MockResponse().setBody(
        """{"items":[{"url":"/watch?v=dQw4w9WgXcQ","type":"stream"}]}""",
      ))
      start()
    }
    val baseUrl = server.url("").toString().trimEnd('/')
    val piped = PipedClient(instances = listOf(baseUrl), client = shortClient())

    val id = piped.searchVideoId("Rick Astley", "Never Gonna Give You Up")

    assertEquals("dQw4w9WgXcQ", id)
    server.shutdown()
  }

  @Test
  fun `walks past dead instance to live one`() = runTest {
    val dead = MockWebServer().apply {
      enqueue(MockResponse().setResponseCode(503))
      start()
    }
    val live = MockWebServer().apply {
      enqueue(MockResponse().setBody(
        """{"items":[{"url":"https://youtu.be/abcDEFghi12","type":"stream"}]}""",
      ))
      start()
    }
    val piped = PipedClient(
      instances = listOf(
        dead.url("").toString().trimEnd('/'),
        live.url("").toString().trimEnd('/'),
      ),
      client = shortClient(),
    )

    val id = piped.searchVideoId("artist", "title")

    // The bare-tail fallback in extractVideoId catches the
    // /abcDEFghi12 path segment.
    assertEquals("abcDEFghi12", id)
    dead.shutdown()
    live.shutdown()
  }

  @Test
  fun `returns null when every instance fails`() = runTest {
    val dead1 = MockWebServer().apply {
      enqueue(MockResponse().setResponseCode(500)); start()
    }
    val dead2 = MockWebServer().apply {
      enqueue(MockResponse().setResponseCode(500)); start()
    }
    val piped = PipedClient(
      instances = listOf(
        dead1.url("").toString().trimEnd('/'),
        dead2.url("").toString().trimEnd('/'),
      ),
      client = shortClient(),
    )

    assertNull(piped.searchVideoId("artist", "title"))
    dead1.shutdown()
    dead2.shutdown()
  }

  @Test
  fun `extractVideoId handles v parameter`() {
    val piped = PipedClient(instances = emptyList())
    assertEquals("dQw4w9WgXcQ", piped.extractVideoId("/watch?v=dQw4w9WgXcQ&t=1s"))
    assertEquals("dQw4w9WgXcQ", piped.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
  }

  @Test
  fun `extractVideoId handles youtu_be path tail`() {
    val piped = PipedClient(instances = emptyList())
    assertEquals("abcDEFghi12", piped.extractVideoId("https://youtu.be/abcDEFghi12"))
  }

  @Test
  fun `extractVideoId returns null on garbage`() {
    val piped = PipedClient(instances = emptyList())
    assertNull(piped.extractVideoId("https://example.com/about"))
    assertNull(piped.extractVideoId(null))
    assertNull(piped.extractVideoId(""))
  }
}
