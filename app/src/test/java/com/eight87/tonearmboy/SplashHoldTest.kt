package com.eight87.tonearmboy

import com.eight87.tonearmboy.playback.ConnectionPhase
import com.eight87.tonearmboy.playback.PlaybackUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * D.22.2 — splash-screen hold state machine.
 *
 * `MainActivity.onCreate` installs the system splash with a
 * `setKeepOnScreenCondition { splashHold.get() }` predicate, then
 * launches a coroutine that:
 *   1. waits up to [MainActivity.SPLASH_HOLD_TIMEOUT_MS] ms for the
 *      Media3 `MediaController` to bind (signalled by
 *      `playback.state.connectionPhase == Connected`),
 *   2. flips `splashHold` to `false` when *either* the controller
 *      binds OR the timeout fires.
 *
 * These tests exercise the same `withTimeoutOrNull(...) {
 * awaitConnected() }` shape against a virtual-time scheduler so the
 * race can be checked without spinning a real activity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplashHoldTest {

  private fun fakeStateFlow(initial: ConnectionPhase = ConnectionPhase.Connecting) =
    MutableStateFlow(PlaybackUiState.Empty.copy(connectionPhase = initial))

  private suspend fun awaitConnected(state: MutableStateFlow<PlaybackUiState>) {
    if (state.value.connectionPhase == ConnectionPhase.Connected) return
    state.first { it.connectionPhase == ConnectionPhase.Connected }
  }

  @Test
  fun splashHold_flips_false_when_controller_binds_before_timeout() = runTest {
    val splashHold = AtomicBoolean(true)
    val state = fakeStateFlow()

    val job = launch {
      withTimeoutOrNull(MainActivity.SPLASH_HOLD_TIMEOUT_MS) {
        awaitConnected(state)
      }
      splashHold.set(false)
    }

    // Simulate Media3 binding 100 ms in — well inside the 150 ms cap.
    advanceTimeBy(100)
    state.value = state.value.copy(connectionPhase = ConnectionPhase.Connected)
    advanceUntilIdle()

    assertFalse("splashHold should clear when controller binds", splashHold.get())
    assertTrue(job.isCompleted)
  }

  @Test
  fun splashHold_flips_false_when_timeout_fires_first() = runTest {
    val splashHold = AtomicBoolean(true)
    val state = fakeStateFlow()
    val timeoutOutcome = AtomicBoolean(false)

    val job = launch {
      val resolved = withTimeoutOrNull(MainActivity.SPLASH_HOLD_TIMEOUT_MS) {
        awaitConnected(state)
        true
      }
      timeoutOutcome.set(resolved == null)
      splashHold.set(false)
    }

    // Never flip the state; just let virtual time advance past the cap.
    advanceTimeBy(MainActivity.SPLASH_HOLD_TIMEOUT_MS + 50)
    advanceUntilIdle()

    assertFalse("splashHold must release on timeout", splashHold.get())
    assertTrue("withTimeoutOrNull should return null when no bind", timeoutOutcome.get())
    assertTrue(job.isCompleted)
  }

  @Test
  fun awaitConnected_returns_immediately_when_already_connected() = runTest {
    val state = fakeStateFlow(initial = ConnectionPhase.Connected)
    // No virtual-time advance needed; runs to completion in the
    // current test scheduler step.
    awaitConnected(state)
  }

  @Test
  fun timeout_constant_matches_documented_value() {
    // Pin the documented value so a future bump in MainActivity
    // (and any matching docs / tests) stays in sync. The cap was
    // dropped from 600 ms → 150 ms (post-D.22.2 polish) once empirical
    // bind latency settled well under that.
    org.junit.Assert.assertEquals(150L, MainActivity.SPLASH_HOLD_TIMEOUT_MS)
  }
}
