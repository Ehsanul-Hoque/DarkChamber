package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureResult
import dev.ehsanfahad.darkchamber.feature.camera.api.CapturedPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Turns shutter presses into saved photos: each press captures, and the saves run one after another
 * in the order the photos arrived.
 *
 * **At most [bound] photos are held in memory at once** — counting from the press until the photo is
 * either in the vault or has failed. A JPEG from a phone camera is several megabytes, so an unbounded
 * queue under a quick thumb is a memory problem; past the bound [shoot] refuses and
 * [CaptureQueueState.shutterEnabled] is false until one completes.
 *
 * **[scope] must be the session's feature scope, not the screen's.** Closing the camera destroys the
 * screen while photos may still be saving; those were taken on purpose and finish. A lock cancels
 * this scope, and then nothing waits: a save in progress is abandoned (its import polls [scope]'s
 * state and stops within a read), every photo still waiting has its bytes zeroed, and a capture still
 * in the platform's hands is never received. A lock does not wait for a save — keeping the keys in
 * memory while the app is in the background would break the lock.
 *
 * Main-thread confined like the screen that drives it: [scope] runs on the main dispatcher, and only
 * the encrypt moves to [ioContext].
 */
internal class CaptureQueue(
    private val camera: CameraController,
    private val save: SavePhoto,
    private val albumId: ByteArray?,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext,
    private val bound: Int = DEFAULT_BOUND,
) {
    private val _state = MutableStateFlow(CaptureQueueState(holding = 0, bound = bound))
    val state: StateFlow<CaptureQueueState> = _state.asStateFlow()

    private val waiting = Channel<CapturedPhoto>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                for (photo in waiting) persist(photo)
            } finally {
                // Closed first, so a capture that lands after this point is refused and cleared by
                // its sender instead of joining a queue nobody will read.
                waiting.close()
                while (true) {
                    val left = waiting.tryReceive().getOrNull() ?: break
                    left.jpeg.fill(0)
                }
            }
        }
    }

    /** Take one photo. Returns `false`, doing nothing, when [bound] photos are already held. */
    fun shoot(): Boolean {
        if (!scope.isActive || !_state.value.shutterEnabled) return false
        _state.update { it.copy(holding = it.holding + 1) }
        scope.launch {
            when (val result = camera.capture()) {
                // A refused send means the queue closed under a lock while this photo was arriving.
                // Not reachable today — this coroutine runs in the same scope, and a cancelled
                // coroutine never resumes from the capture with a value — so no test can reach it;
                // kept because if it ever is reached, what it would otherwise drop is plaintext.
                is CaptureResult.Captured ->
                    if (waiting.trySend(result.photo).isFailure) result.photo.jpeg.fill(0)
                CaptureResult.Failed ->
                    _state.update { it.copy(holding = it.holding - 1, problem = CaptureProblem.CaptureFailed) }
            }
        }
        return true
    }

    private suspend fun persist(photo: CapturedPhoto) {
        val saved = withContext(ioContext) { save(photo, albumId) { scope.isActive } }
        _state.update {
            when (saved) {
                is Outcome.Ok -> it.copy(holding = it.holding - 1, saved = it.saved + 1, lastSavedId = saved.value, problem = null)
                is Outcome.Err -> it.copy(holding = it.holding - 1, problem = CaptureProblem.SaveFailed)
            }
        }
    }

    companion object {
        /**
         * Three photos: a quick burst never waits on the shutter, and three full-resolution JPEGs
         * plus the encrypt's working memory stay well inside a phone's heap. To be confirmed against
         * real capture sizes on a device.
         */
        const val DEFAULT_BOUND: Int = 3
    }
}

/** What went wrong most recently, until a photo is saved again. */
internal enum class CaptureProblem { CaptureFailed, SaveFailed }

/**
 * The queue as the screen sees it: how many photos are held, how many this camera has saved, the
 * id of the newest one, and the most recent problem.
 *
 * Holds an item **id**, never image bytes — screen state outlives nothing, but it is the one place a
 * stray reference would be easiest to keep by accident.
 */
internal class CaptureQueueState(
    val holding: Int,
    val bound: Int,
    val saved: Int = 0,
    val lastSavedId: ByteArray? = null,
    val problem: CaptureProblem? = null,
) {
    val shutterEnabled: Boolean get() = holding < bound

    fun copy(
        holding: Int = this.holding,
        saved: Int = this.saved,
        lastSavedId: ByteArray? = this.lastSavedId,
        problem: CaptureProblem? = this.problem,
    ): CaptureQueueState = CaptureQueueState(holding, bound, saved, lastSavedId, problem)

    override fun toString(): String =
        "CaptureQueueState(holding=$holding/$bound, saved=$saved, " +
            "last=${if (lastSavedId == null) "none" else "present"}, problem=$problem)"
}
