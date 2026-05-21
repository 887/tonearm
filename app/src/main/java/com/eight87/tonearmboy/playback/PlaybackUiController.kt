package com.eight87.tonearmboy.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.eight87.tonearmboy.data.TrackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connection lifecycle + listener attach + facet composition.
 * Behaviour lives in the four collaborators ([projector] reads;
 * [transportImpl] / [queueImpl] / [replayGainController] write).
 *
 * R.C.9 — class delegation drops the 25 forwarding overrides
 * R.C.3 left behind. Constructor is private; build through the
 * factory in [Companion.invoke] which wires every collaborator
 * around a shared [ControllerHolder].
 */
@UnstableApi
class PlaybackUiController private constructor(
  private val applicationContext: Context,
  private val scope: CoroutineScope,
  private val controllerHolder: ControllerHolder,
  private val phaseHolder: ConnectionPhaseHolder,
  private val projector: PlaybackStateProjector,
  private val replayGainController: ReplayGainController,
  private val listener: Player.Listener,
  transportImpl: MediaTransportCommands,
  queueImpl: MediaQueueCommands,
) : NowPlayingState,
    TransportCommands by transportImpl,
    QueueCommands by queueImpl,
    ReplayGainCommands by replayGainController {

  override val state: StateFlow<PlaybackUiState> get() = projector.state
  override val queue: StateFlow<QueueSnapshot> get() = projector.queue
  override val audioSessionId: StateFlow<Int> = PlayerHolder.audioSessionId

  /** R.C.4 — late library binding for ReplayGain album lookups. */
  fun setLibrary(repo: TrackSource) = replayGainController.setLibrary(repo)

  // R.C.5 — SleepTimer hooks. Inline forwards so AppGraph can build
  // the timer with closures over these three methods.
  fun pauseSilently() { scope.launch { controllerHolder.value?.pause() } }
  fun addPlayerListener(l: Player.Listener) { controllerHolder.value?.addListener(l) }
  fun removePlayerListener(l: Player.Listener) { controllerHolder.value?.removeListener(l) }

  /**
   * D.22.2 — suspend until handshake reports Connected. Bounded by
   * the caller's `withTimeoutOrNull` so a stuck binding doesn't pin
   * the splash forever.
   */
  suspend fun awaitConnected() {
    if (state.value.connectionPhase == ConnectionPhase.Connected) return
    state.first { it.connectionPhase == ConnectionPhase.Connected }
  }

  private var positionTickerStarted = false

  /** Connect to the running [PlaybackService]. Idempotent. */
  suspend fun connect() = withContext(Dispatchers.Main) {
    if (controllerHolder.value != null) return@withContext
    val c = PlaybackController.connect(applicationContext).await()
    controllerHolder.value = c
    c.addListener(listener)
    // D.22.3: flip Connecting → Connected before pushing so the
    // first emitted state carries the right phase.
    phaseHolder.value = ConnectionPhase.Connected
    // Apply persisted ReplayGain to the freshly bound player.
    scope.launch { replayGainController.applyReplayGainNow() }
    projector.pushAll()
    if (!positionTickerStarted) {
      positionTickerStarted = true
      scope.launch {
        // 250 ms tick — cheap projector.pushPlaybackState() only;
        // listener events drive queue rebuilds (R.C.2).
        while (true) {
          delay(POSITION_TICK_MS)
          val ctl = controllerHolder.value ?: continue
          if (ctl.isPlaying) projector.pushPlaybackState()
        }
      }
    }
  }

  /** Release the controller. Called from `DisposableEffect`'s onDispose. */
  fun release() {
    controllerHolder.value?.let {
      it.removeListener(listener)
      it.release()
    }
    controllerHolder.value = null
    phaseHolder.value = ConnectionPhase.Connecting
    projector.reset()
  }

  fun shutdown() {
    release()
    scope.cancel()
  }

  companion object {
    private const val POSITION_TICK_MS = 250L

    /**
     * D.15.7 — extras key carrying the MediaStore album id through
     * to the [androidx.media3.common.MediaItem.mediaMetadata]. The
     * Now Playing screen reads this to drive the same `CoverArt`
     * composable the library tabs use.
     */
    const val EXTRA_MEDIA_STORE_ALBUM_ID = "tonearmboy.mediaStoreAlbumId"

    /**
     * Extras key carrying the local Track.id so the BitmapLoader can
     * resolve any user-pinned / bulk-fetched cover from the LibraryRepository
     * for lock-screen + notification surfaces.
     */
    const val EXTRA_TRACK_ID = "tonearmboy.trackId"

    /** R.C.9 — factory that wires every collaborator around a shared holder. */
    operator fun invoke(applicationContext: Context): PlaybackUiController {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
      val controllerHolder = ControllerHolder()
      val phaseHolder = ConnectionPhaseHolder()
      val pauseOnRepeat = PauseOnRepeatHolder()
      val projector = PlaybackStateProjector(
        controllerProvider = { controllerHolder.value },
        connectionPhaseProvider = { phaseHolder.value },
      )
      val replayGain = ReplayGainController(scope) { controllerHolder.value }
      val transport = MediaTransportCommands(
        controllerProvider = { controllerHolder.value },
        projector = projector,
        pauseOnRepeatHolder = pauseOnRepeat,
      )
      val queue = MediaQueueCommands(
        controllerProvider = { controllerHolder.value },
        projector = projector,
      )
      val listener = PlaybackPlayerListener(
        scope = scope,
        projector = projector,
        replayGainController = replayGain,
        pauseOnRepeatHolder = pauseOnRepeat,
        controllerProvider = { controllerHolder.value },
      )
      return PlaybackUiController(
        applicationContext, scope, controllerHolder, phaseHolder,
        projector, replayGain, listener, transport, queue,
      )
    }
  }
}

/** Mutable holder for the live `MediaController` shared across collaborators. */
internal class ControllerHolder {
  @Volatile var value: MediaController? = null
}

/** Mutable holder for the handshake phase shared with the projector. */
internal class ConnectionPhaseHolder {
  @Volatile var value: ConnectionPhase = ConnectionPhase.Connecting
}
