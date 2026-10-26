package dev.ehsanfahad.darkchamber.feature.camera

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
import kotlinx.io.files.Path

/**
 * A scripted camera for host and simulator tests.
 *
 * Every state the platform can report is set directly, every call is counted, and a capture can be
 * **held** until the test releases it — so a queue's ordering, its bound and a lock arriving
 * mid-capture are driven by the test, never by elapsed time.
 */
internal class FakeCameraController(
    permission: CameraPermission = CameraPermission.Granted,
    availability: CameraAvailability = CameraAvailability.Available,
    capabilities: CameraCapabilities = CameraCapabilities(
        lenses = setOf(Lens.Back, Lens.Front),
        hasFlash = true,
        minZoom = 1f,
        maxZoom = 8f,
        videoQualities = setOf(VideoQuality.HD, VideoQuality.UHD),
    ),
    microphonePermission: MicrophonePermission = MicrophonePermission.Granted,
) : CameraController {
    override val permission = MutableStateFlow(permission)
    override val availability = MutableStateFlow(availability)
    override val capabilities = MutableStateFlow(capabilities)
    override val microphonePermission = MutableStateFlow(microphonePermission)

    override var videoContainer: VideoContainer = VideoContainer.Mp4

    /** Set by the test as the device's tilt would be. */
    override val deviceRotation = MutableStateFlow(0)

    /** What the next [requestMicrophonePermission] resolves to. */
    var microphoneAnswer: MicrophonePermission = MicrophonePermission.Granted

    /** As [platformPermission], for the microphone. */
    var platformMicrophonePermission: MicrophonePermission? = null

    var microphoneRequests = 0
        private set

    /** What the next [requestPermission] resolves to. */
    var permissionAnswer: CameraPermission = CameraPermission.Granted

    var permissionRequests = 0
        private set
    var settingsOpened = 0
        private set
    var starts = 0
        private set
    var stops = 0
        private set
    var captures = 0
        private set

    var lens: Lens = Lens.Back
        private set
    var flash: FlashMode = FlashMode.Off
        private set
    var zoom: Float = 1f
        private set
    val focusPoints = mutableListOf<Pair<Float, Float>>()
    var mode: CaptureMode = CaptureMode.Photo
        private set
    var quality: VideoQuality = VideoQuality.HD
        private set
    var torch: Boolean = false
        private set

    /** What one [record] call asked for. */
    data class RecordRequest(val file: Path, val sizeLimitBytes: Long, val withAudio: Boolean)

    val recordRequests = mutableListOf<RecordRequest>()

    /**
     * How a recording ends when it is stopped. `null` makes the platform never answer, so the test
     * ends it through [lastRecording] — the way a finalize that is slow, or never comes, is driven.
     */
    var endOnStop: RecordingEnd? = RecordingEnd.Finalized

    /** How many recordings were asked to stop. */
    var recordingStops = 0
        private set

    /** The latest recording that started, for the test to report progress into and end. */
    var lastRecording: ReportedRecording? = null
        private set

    private val recording: ReportedRecording? get() = lastRecording?.takeUnless { it.isEnded }

    /** True between [start] and [stop] — what the platform's in-use indicator would show. */
    val isOpen: Boolean get() = starts > stops

    /**
     * Produces each capture's result. Defaults to a fresh small JPEG-shaped array per call, so no two
     * captures share an instance — a test that zeroes one must not see another change.
     */
    var nextCapture: suspend () -> CaptureResult = {
        CaptureResult.Captured(CapturedPhoto(jpegLike(captures), rotationDegrees = 0))
    }

    private val held = ArrayDeque<CompletableDeferred<CaptureResult>>()

    /** From now on, every capture suspends until [release] answers it, oldest first. */
    fun holdCaptures() {
        nextCapture = {
            val gate = CompletableDeferred<CaptureResult>()
            held.addLast(gate)
            gate.await()
        }
    }

    /** How many captures are waiting on [release]. */
    val heldCount: Int get() = held.size

    /** Answer the oldest held capture. */
    fun release(result: CaptureResult = CaptureResult.Captured(CapturedPhoto(jpegLike(captures), 0))) {
        held.removeFirst().complete(result)
    }

    override suspend fun requestPermission() {
        permissionRequests++
        permission.value = permissionAnswer
    }

    override suspend fun requestMicrophonePermission() {
        microphoneRequests++
        microphonePermission.value = microphoneAnswer
    }

    override fun openSettings() {
        settingsOpened++
    }

    /**
     * What the platform would answer if asked now — changed in the system's settings, say, while the
     * app was away. Like the real seams, [start] reads it before deciding; `null` leaves [permission]
     * as the test set it.
     */
    var platformPermission: CameraPermission? = null

    /** As the seam promises: a no-op without permission, which it reads afresh first. */
    override fun start() {
        platformPermission?.let { permission.value = it }
        platformMicrophonePermission?.let { microphonePermission.value = it }
        if (isOpen || permission.value != CameraPermission.Granted) return
        starts++
    }

    /** As the seam promises: a running recording is abandoned, not finalized. */
    override fun stop() {
        if (!isOpen) return
        recording?.finish(RecordingEnd.Abandoned)
        stops++
    }

    override fun setMode(mode: CaptureMode) {
        this.mode = mode
    }

    override fun setQuality(quality: VideoQuality) {
        this.quality = quality
    }

    override fun setTorch(on: Boolean) {
        if (capabilities.value.hasFlash) torch = on
    }

    override fun record(file: Path, sizeLimitBytes: Long, withAudio: Boolean): Recording {
        recordRequests += RecordRequest(file, sizeLimitBytes, withAudio)
        if (!isOpen || mode != CaptureMode.Video || recording != null) return ReportedRecording.refused()
        lateinit var started: ReportedRecording
        started = ReportedRecording(onStop = {
            recordingStops++
            endOnStop?.let { started.finish(it) }
        })
        lastRecording = started
        return started
    }

    override fun setLens(lens: Lens) {
        if (lens in capabilities.value.lenses) this.lens = lens
    }

    override fun setFlash(mode: FlashMode) {
        if (capabilities.value.hasFlash) flash = mode
    }

    override fun setZoom(ratio: Float) {
        val caps = capabilities.value
        zoom = ratio.coerceIn(caps.minZoom, caps.maxZoom)
    }

    override fun focusAt(x: Float, y: Float) {
        focusPoints += x to y
    }

    override suspend fun capture(): CaptureResult {
        captures++
        return nextCapture()
    }

    @Composable
    override fun Preview(modifier: Modifier) = Unit
}

/** A JPEG-shaped array (SOI … EOI) whose middle byte tells captures apart. */
internal fun jpegLike(seed: Int): ByteArray =
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), seed.toByte(), 0xFF.toByte(), 0xD9.toByte())
