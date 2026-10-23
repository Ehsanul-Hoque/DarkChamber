package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import dev.ehsanfahad.darkchamber.core.model.NotSavedReporter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
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
 *
 * **What did not make it is reported to [notSaved]**, which outlives the session, so it can be told
 * once the vault is unlocked again: every photo a lock abandoned, and — once the camera has closed and
 * no screen is left to say so — a photo whose save failed. While the camera is open a failure is its
 * screen's to show, through [CaptureQueueState.problem].
 */
internal class CaptureQueue(
    private val camera: CameraController,
    private val save: SavePhoto,
    private val albumId: ByteArray?,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext,
    private val notSaved: NotSavedReporter,
    private val bound: Int = DEFAULT_BOUND,
) {
    private val _state = MutableStateFlow(CaptureQueueState(holding = 0, bound = bound))
    val state: StateFlow<CaptureQueueState> = _state.asStateFlow()

    // A photo handed to the saver as a lock cancels it never reaches the saver, and is in the queue
    // no longer, so the drain below cannot find it: the channel clears it instead.
    private val waiting = Channel<CapturedPhoto>(Channel.UNLIMITED) { it.jpeg.fill(0) }

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
                // Only a lock ends this loop with the scope cancelled; a finished camera ends it with
                // nothing held. Every photo still counted — waiting, mid-encrypt, or in the
                // platform's hands — was a press that never reached the vault.
                val abandoned = _state.value.holding
                if (!scope.isActive && abandoned > 0) {
                    notSaved.report(NotSavedKind.Photo, NotSavedReason.Locked, abandoned)
                }
            }
        }
    }

    private var finishing = false

    /** Take one photo. Returns `false`, doing nothing, when [bound] photos are already held. */
    fun shoot(): Boolean {
        if (finishing || !scope.isActive || !_state.value.shutterEnabled) return false
        _state.update { it.copy(holding = it.holding + 1) }
        scope.launch {
            when (val result = camera.capture()) {
                // A refused send means the queue closed under a lock while this photo was arriving.
                // Not reachable today — this coroutine runs in the same scope, and a cancelled
                // coroutine never resumes from the capture with a value — so no test can reach it;
                // kept because if it ever is reached, what it would otherwise drop is plaintext.
                is CaptureResult.Captured ->
                    if (waiting.trySend(result.photo).isFailure) result.photo.jpeg.fill(0)
                CaptureResult.Failed -> {
                    _state.update { it.copy(holding = it.holding - 1, problem = CaptureProblem.CaptureFailed) }
                    stopIfFinished()
                }
            }
        }
        return true
    }

    /**
     * The camera has closed: take no more photos, save the ones already held, then stop.
     *
     * Not a cancel — those photos were taken on purpose. Without it the queue would wait for photos
     * in the session's scope until the next lock, one idle queue for every time the camera opened.
     */
    fun finish() {
        finishing = true
        stopIfFinished()
    }

    private suspend fun persist(photo: CapturedPhoto) {
        // Cleared here as well as by the save: a lock landing before the encrypt starts on its own
        // thread cancels the save before it runs, and its clearing with it.
        val saved = try {
            withContext(ioContext) { save(photo, albumId) { scope.isActive } }
        } finally {
            photo.jpeg.fill(0)
        }
        _state.update {
            when (saved) {
                is Outcome.Ok -> it.copy(holding = it.holding - 1, saved = it.saved + 1, lastSavedId = saved.value, problem = null)
                is Outcome.Err -> it.copy(
                    holding = it.holding - 1,
                    problem = if (saved.error == VaultError.Storage.Full) CaptureProblem.OutOfSpace else CaptureProblem.SaveFailed,
                )
            }
        }
        // The screen that would have said so has gone, so it is said after instead.
        if (saved is Outcome.Err && finishing) {
            val reason = if (saved.error == VaultError.Storage.Full) NotSavedReason.OutOfSpace else NotSavedReason.CouldNotSave
            notSaved.report(NotSavedKind.Photo, reason, 1)
        }
        stopIfFinished()
    }

    // Only once nothing is held: a photo is counted from its press, so at zero none is in the
    // platform's hands and none is waiting, and closing the channel can refuse nobody.
    private fun stopIfFinished() {
        if (finishing && _state.value.holding == 0) waiting.close()
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

/**
 * What went wrong most recently, until a photo is saved again. [OutOfSpace] only when the vault
 * said the disk was full; every other save failure is [SaveFailed], naming no cause.
 */
internal enum class CaptureProblem { CaptureFailed, SaveFailed, OutOfSpace }

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
