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
import dev.ehsanfahad.darkchamber.feature.camera.api.ReportedRecording
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path

/**
 * A granted, available camera for the host's tests: whether it is open, and captures that can be
 * held until the test releases them — so "a save still in flight" is a state the test sets up, not
 * a race. (The feature's own fake lives in its test source set, which this module cannot see.)
 */
class FakeCameraController : CameraController {
    override val permission = MutableStateFlow(CameraPermission.Granted)
    override val availability = MutableStateFlow(CameraAvailability.Available)
    override val capabilities = MutableStateFlow(
        CameraCapabilities(lenses = setOf(Lens.Back), hasFlash = false, minZoom = 1f, maxZoom = 1f),
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
    override val deviceRotation = MutableStateFlow(0)

    override suspend fun requestPermission() = Unit

    override suspend fun requestMicrophonePermission() = Unit

    override fun openSettings() = Unit

    override fun start() {
        if (!isOpen) starts++
    }

    override fun stop() {
        if (isOpen) stops++
    }

    override fun setMode(mode: CaptureMode) = Unit

    override fun setQuality(quality: VideoQuality) = Unit

    override fun setTorch(on: Boolean) = Unit

    override fun record(file: Path, sizeLimitBytes: Long, withAudio: Boolean): Recording = ReportedRecording.refused()

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
