package dev.ehsanfahad.darkchamber.app.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureResult
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.Recording
import dev.ehsanfahad.darkchamber.feature.camera.api.ReportedRecording
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoContainer
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/**
 * The camera when the platform module binds none: a camera that is not available, saying so.
 *
 * The same reasoning as the thumbnail encoder's fallback. Resolving the seam strictly would turn a
 * platform module that forgot its binding into a crash on the gallery's camera button; this turns
 * it into the screen's ordinary "Camera isn't available right now", which names no cause it cannot
 * know. The platform-module tests are what stop a missing binding going unnoticed.
 *
 * Reports the permission as granted so that no system prompt is raised for a camera that does not
 * exist — the explainer is then the availability one, which is the true statement.
 */
internal object UnavailableCamera : CameraController {
    override val permission: StateFlow<CameraPermission> = MutableStateFlow(CameraPermission.Granted)
    override val availability: StateFlow<CameraAvailability> = MutableStateFlow(CameraAvailability.Unavailable)
    override val capabilities: StateFlow<CameraCapabilities> = MutableStateFlow(CameraCapabilities.None)

    // Granted for the same reason as the camera's: no prompt for a microphone nothing will record with.
    override val microphonePermission: StateFlow<MicrophonePermission> = MutableStateFlow(MicrophonePermission.Granted)
    override val videoContainer: VideoContainer = VideoContainer.Mp4
    override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)

    override suspend fun requestPermission() = Unit

    override suspend fun requestMicrophonePermission() = Unit

    override fun openSettings() = Unit

    override fun start() = Unit

    override fun stop() = Unit

    override fun setMode(mode: CaptureMode) = Unit

    override fun setQuality(quality: VideoQuality) = Unit

    override fun setTorch(on: Boolean) = Unit

    override fun setLens(lens: Lens) = Unit

    override fun setFlash(mode: FlashMode) = Unit

    override fun setZoom(ratio: Float) = Unit

    override fun focusAt(x: Float, y: Float) = Unit

    override suspend fun capture(): CaptureResult = CaptureResult.Failed

    override fun record(file: Path, sizeLimitBytes: Long, withAudio: Boolean): Recording = ReportedRecording.refused()

    @Composable
    override fun Preview(modifier: Modifier) = Unit
}
