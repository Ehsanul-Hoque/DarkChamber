package dev.ehsanfahad.darkchamber.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureResult
import dev.ehsanfahad.darkchamber.feature.camera.api.CapturedPhoto
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.Recording
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingEnd
import dev.ehsanfahad.darkchamber.feature.camera.api.ReportedRecording
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoContainer
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * A granted, available camera for the host's tests: whether it is open, captures that can be held
 * until the test releases them — so "a save still in flight" is a state the test sets up, not a race
 * — and recordings whose finalize can be held the same way. (The feature's own fake lives in its test
 * source set, which this module cannot see.)
 */
class FakeCameraController : CameraController {
    override val permission = MutableStateFlow(CameraPermission.Granted)
    override val availability = MutableStateFlow(CameraAvailability.Available)
    override val capabilities = MutableStateFlow(
        CameraCapabilities(
            lenses = setOf(Lens.Back),
            hasFlash = false,
            minZoom = 1f,
            maxZoom = 1f,
            videoQualities = setOf(VideoQuality.HD),
        ),
    )

    var starts = 0
        private set
    var stops = 0
        private set

    val isOpen: Boolean get() = starts > stops

    private var holding = false
    private val held = ArrayDeque<CompletableDeferred<CaptureResult>>()

    /** From now on every capture waits for [release]. */
    fun holdCaptures() {
        holding = true
    }

    /** Answer the oldest held capture with a small JPEG-shaped photo. */
    fun release() {
        held.removeFirst().complete(CaptureResult.Captured(CapturedPhoto(jpeg(), rotationDegrees = 0)))
    }

    override val microphonePermission = MutableStateFlow(MicrophonePermission.Granted)
    override val videoContainer = VideoContainer.Mp4
    override val deviceRotation = MutableStateFlow(0)

    override suspend fun requestPermission() = Unit

    override suspend fun requestMicrophonePermission() = Unit

    override fun openSettings() = Unit

    override fun start() {
        if (!isOpen) starts++
    }

    /** As the seam promises: releasing the camera abandons a recording in progress. */
    override fun stop() {
        if (!isOpen) return
        recording?.finish(RecordingEnd.Abandoned)
        stops++
    }

    var mode: CaptureMode = CaptureMode.Photo
        private set

    override fun setMode(mode: CaptureMode) {
        this.mode = mode
    }

    override fun setQuality(quality: VideoQuality) = Unit

    override fun setTorch(on: Boolean) = Unit

    /**
     * How a recording ends when stopped; `null` holds the finalize until the test ends it through
     * [recording] — a platform still writing out the clip.
     */
    var endOnStop: RecordingEnd? = RecordingEnd.Finalized

    /** The recording in progress, if any, for the test to end. */
    var recording: ReportedRecording? = null
        private set

    /**
     * Records as the platform does, into the file it is handed and nowhere else: a small clip is
     * written there at the start, so the save that follows reads a real file.
     */
    override fun record(file: Path, sizeLimitBytes: Long, withAudio: Boolean): Recording {
        if (!isOpen || mode != CaptureMode.Video || recording?.isEnded == false) return ReportedRecording.refused()
        SystemFileSystem.sink(file).buffered().use { it.write(ByteArray(2_000) { n -> n.toByte() }) }
        lateinit var started: ReportedRecording
        started = ReportedRecording(onStop = { endOnStop?.let { started.finish(it) } })
        recording = started
        return started
    }

    override fun setLens(lens: Lens) = Unit

    override fun setFlash(mode: FlashMode) = Unit

    override fun setZoom(ratio: Float) = Unit

    override fun focusAt(x: Float, y: Float) = Unit

    override suspend fun capture(): CaptureResult {
        if (!holding) return CaptureResult.Captured(CapturedPhoto(jpeg(), rotationDegrees = 0))
        val gate = CompletableDeferred<CaptureResult>()
        held.addLast(gate)
        return gate.await()
    }

    @Composable
    override fun Preview(modifier: Modifier) = Unit

    private fun jpeg(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())
}
