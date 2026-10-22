package dev.ehsanfahad.darkchamber.feature.camera.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/**
 * One video recording in progress, as [CameraController.record] hands it back.
 *
 * Its end is a typed [RecordingEnd], never an exception, and arrives exactly once — whichever the
 * platform reported first. [stop] is the *keep it* path: it asks the platform to finalize the file
 * and the end follows. The camera's own [CameraController.stop] is the teardown a lock runs, and
 * ends a recording as [RecordingEnd.Abandoned] instead.
 */
interface Recording {
    /** How much has been written so far. Stops moving once the recording has ended. */
    val progress: StateFlow<RecordingProgress>

    /** Suspend until the recording has ended, and say how. */
    suspend fun awaitEnd(): RecordingEnd

    /** Ask the platform to finalize the file. Idempotent, and a no-op once the recording has ended. */
    fun stop()
}

/**
 * A [Recording] the platform reports into: a controller builds one per recording, passes its
 * camera stack's events to [report] and [finish], and gives it the platform's own stop call. Shared
 * by every implementation so the one-end rule is written, and tested, once.
 *
 * Main-thread confined, like the controller that owns it.
 */
class ReportedRecording(private val onStop: () -> Unit) : Recording {
    private val _progress = MutableStateFlow(RecordingProgress.Zero)
    override val progress: StateFlow<RecordingProgress> = _progress.asStateFlow()

    private val end = CompletableDeferred<RecordingEnd>()
    private var stopAsked = false

    /** Whether an end has been reported. */
    val isEnded: Boolean get() = end.isCompleted

    override suspend fun awaitEnd(): RecordingEnd = end.await()

    override fun stop() {
        if (stopAsked || isEnded) return
        stopAsked = true
        onStop()
    }

    /** The platform's latest count. Ignored after the end, so the last figure is the final one. */
    fun report(progress: RecordingProgress) {
        if (!isEnded) _progress.value = progress
    }

    /** Report the end. Returns false, and changes nothing, when an end was already reported. */
    fun finish(end: RecordingEnd): Boolean = this.end.complete(end)

    companion object {
        /** A recording that could not start: already ended, as [RecordingFailure.Other]. */
        fun refused(): Recording =
            ReportedRecording(onStop = {}).apply { finish(RecordingEnd.Failed(RecordingFailure.Other)) }
    }
}

/** How far a recording has got: bytes in the file so far, and the length of the clip they hold. */
data class RecordingProgress(val bytesRecorded: Long, val duration: Duration) {
    companion object {
        val Zero: RecordingProgress = RecordingProgress(0, Duration.ZERO)
    }
}

/** How a recording ended. Each case says what is left in the file it was given. */
sealed interface RecordingEnd {
    /** Stopped when asked; the file holds the whole clip. */
    data object Finalized : RecordingEnd

    /** The platform stopped at the size limit it was given; the file holds the clip up to there. */
    data object FinalizedAtSizeLimit : RecordingEnd

    /** Stopped before the first frame arrived; there is no clip, whatever the file holds. */
    data object NoFrames : RecordingEnd

    /**
     * The platform ended the recording with an error. With [RecordingFailure.StorageFull] the file may
     * still hold a playable clip, which is worth one attempt to save; with [RecordingFailure.Other] it
     * is not to be trusted. Carries no platform message, for the same reason [CaptureResult.Failed]
     * does not.
     */
    data class Failed(val reason: RecordingFailure) : RecordingEnd

    /** Torn down without finalizing — a lock, or the camera released mid-recording. Nothing is kept. */
    data object Abandoned : RecordingEnd
}

/** Why a recording failed: the one cause the screen can name, and everything else. */
enum class RecordingFailure { StorageFull, Other }
