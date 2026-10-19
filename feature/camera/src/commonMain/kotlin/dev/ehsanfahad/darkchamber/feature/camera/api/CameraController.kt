package dev.ehsanfahad.darkchamber.feature.camera.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * The platform seam for the camera — CameraX on Android, AVFoundation on iOS. The implementations
 * live in the app module, which owns the activity and the view controller, the same way the pickers'
 * and the thumbnail encoder's do; this module only states the contract and draws the screen around
 * it.
 *
 * A plain interface rather than `expect`/`actual`: an implementation needs platform state this
 * module cannot reach (an activity's lifecycle, a capture session), and tests drive every path —
 * a denied permission, a camera in use, a capture that fails or takes its time — through a fake.
 *
 * **The one rule every implementation keeps: a capture never touches a file.** The photo arrives in
 * [capture]'s result as bytes in memory and goes from there straight into the vault's encrypting
 * import. Both platforms offer a "save to file" capture as the convenient path, and both would put
 * plaintext on disk (hard rule #1) — CameraX's own file saver even stages through a temporary file
 * when it is handed a stream. Location is never attached to a capture either; the app holds no
 * location permission.
 *
 * Main-thread confined, like the pickers: the screen drives it from its own lifecycle, and the
 * platform camera stacks expect configuration calls from the thread that owns the view.
 */
interface CameraController {
    /** Whether the app may use the camera. Starts as the platform's current answer. */
    val permission: StateFlow<CameraPermission>

    /** Whether a camera can be opened right now. Meaningful only once [permission] is granted. */
    val availability: StateFlow<CameraAvailability>

    /** What the open camera supports. [CameraCapabilities.None] until one has opened. */
    val capabilities: StateFlow<CameraCapabilities>

    /**
     * Ask for the camera permission and suspend until the user answers; [permission] carries the
     * result. On Android the prompt is a dialog over this app, so it does not background it.
     */
    suspend fun requestPermission()

    /**
     * Open the platform's settings page for this app — the only place a permanently denied
     * permission can be changed. Leaving for it backgrounds the app, so the vault locks; that is the
     * ordinary lock-on-background rule, not something to work around.
     */
    fun openSettings()

    /**
     * Open the camera and start the preview. Called when the screen starts; a no-op without
     * permission. Idempotent.
     */
    fun start()

    /**
     * Release the camera, so the platform's in-use indicator goes off. Called when the screen stops
     * and when it is destroyed — including by a lock tearing the screen down. Idempotent.
     */
    fun stop()

    /** Switch lens. Ignored for a lens [capabilities] does not list. */
    fun setLens(lens: Lens)

    /** The flash setting the next capture uses. Ignored when the current lens has no flash. */
    fun setFlash(mode: FlashMode)

    /** Zoom to [ratio], clamped by the implementation to the range [capabilities] reports. */
    fun setZoom(ratio: Float)

    /**
     * Focus and meter at a point of the preview, given as fractions of its width and height
     * (0..1 each), so the caller never needs to know the preview's pixel size or how it is cropped.
     */
    fun focusAt(x: Float, y: Float)

    /**
     * Take one photo and suspend until the platform hands back its JPEG. Never writes a file. A
     * failure is [CaptureResult.Failed], not an exception.
     */
    suspend fun capture(): CaptureResult

    /**
     * The live viewfinder, drawn by the platform's own preview view. Draws nothing until [start]
     * has opened a camera.
     */
    @Composable
    fun Preview(modifier: Modifier)
}
