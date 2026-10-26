package dev.ehsanfahad.darkchamber.feature.camera.api

import com.arkivanov.decompose.value.Value
import dev.ehsanfahad.darkchamber.feature.camera.presentation.CameraState
import kotlinx.coroutines.flow.StateFlow

/**
 * The camera screen's component: it opens the camera while the screen is started, turns shutter
 * presses into photos saved in the vault, and shows where they are going and how many are there.
 *
 * Presentation follows the gallery's style (docs/engineering/PATTERNS.md §3) — the component is the
 * state holder, on a lifecycle-scoped coroutine scope, and it exists only inside the unlocked subtree.
 * It does not navigate: [close] reports through the callback the host supplied.
 *
 * An abstract class rather than an interface so that what the screen draws from can stay `internal`:
 * the host builds one, hands it to the screen, and never reads its state. For the same reason no
 * other module can implement it.
 */
abstract class CameraComponent internal constructor() {

    /** Where this camera's photos go — the listing it was opened from. Fixed for its lifetime. */
    abstract val destination: CameraDestination

    internal abstract val state: Value<CameraState>

    /** The seam, for the screen to draw its viewfinder. */
    internal abstract val camera: CameraController

    /** The host's sentence for a name that will not open; the camera never spells it itself. */
    internal abstract val unreadableNameLabel: String

    /** The device's tilt, for the icons that turn with it. */
    internal abstract val tilt: StateFlow<Int>

    /**
     * Press the shutter: take a photo, start recording, or stop — whatever the shutter shows. Ignored
     * whenever it is shown disabled.
     */
    abstract fun shoot()

    /** Photo or video. Ignored while recording; the first switch to video asks for the microphone. */
    abstract fun setMode(mode: CaptureMode)

    /** Switch to the quality the chip offers. Ignored when the chip is not shown. */
    abstract fun switchQuality()

    /** Switch to the other lens. Ignored on a camera with only one, and while recording. */
    abstract fun flipLens()

    /** Step the flash off → auto → on → off. Ignored when the lens in use has no flash, and in video. */
    abstract fun cycleFlash()

    /** Turn the torch on or off, in video. Ignored when the lens in use has no light. */
    abstract fun toggleTorch()

    /** The microphone hint's action: ask again, or open Settings once the system will not ask. */
    abstract fun requestMicrophone()

    /**
     * The microphone toggle: sound on or off for the next recordings, remembered for the session.
     * While the microphone is refused a tap asks for it instead — re-ask, or Settings — since flipping
     * the toggle could not give sound. Ignored where the toggle is not shown, including while recording.
     */
    abstract fun toggleSound()

    /** Zoom to [ratio], kept within the range the camera reports. */
    abstract fun zoomTo(ratio: Float)

    /** Focus at a point of the viewfinder, as fractions of its width and height. */
    abstract fun focusAt(x: Float, y: Float)

    /** Ask for the camera permission again — the explainer's "Allow". */
    abstract fun requestPermission()

    /** Open this app's system settings — the explainer's way out of a permanent refusal. */
    abstract fun openSettings()

    /**
     * Leave the camera. Photos and clips already taken keep saving; the host pops the screen. A
     * recording in progress is kept: it is finalized and handed to the save lane first, and only then
     * is the camera released and the screen popped. A second call does nothing, so a double tap
     * cannot pop the screen underneath too.
     */
    abstract fun close()
}
