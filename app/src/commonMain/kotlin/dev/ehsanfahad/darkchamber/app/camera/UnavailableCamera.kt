package dev.ehsanfahad.darkchamber.app.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureResult
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    override suspend fun requestPermission() = Unit

    override fun openSettings() = Unit

    override fun start() = Unit

    override fun stop() = Unit

    override fun setLens(lens: Lens) = Unit

    override fun setFlash(mode: FlashMode) = Unit

    override fun setZoom(ratio: Float) = Unit

    override fun focusAt(x: Float, y: Float) = Unit

    override suspend fun capture(): CaptureResult = CaptureResult.Failed

    @Composable
    override fun Preview(modifier: Modifier) = Unit
}
