package dev.ehsanfahad.darkchamber.feature.camera.api

import com.arkivanov.decompose.value.Value
import dev.ehsanfahad.darkchamber.feature.camera.presentation.CameraState

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

    /** Take one photo. Ignored whenever the shutter is shown disabled. */
    abstract fun shoot()

    /** Switch to the other lens. Ignored on a camera with only one. */
    abstract fun flipLens()

    /** Step the flash off → auto → on → off. Ignored when the lens in use has no flash. */
    abstract fun cycleFlash()

    /** Zoom to [ratio], kept within the range the camera reports. */
    abstract fun zoomTo(ratio: Float)

    /** Focus at a point of the viewfinder, as fractions of its width and height. */
    abstract fun focusAt(x: Float, y: Float)

    /** Ask for the camera permission again — the explainer's "Allow". */
    abstract fun requestPermission()

    /** Open this app's system settings — the explainer's way out of a permanent refusal. */
    abstract fun openSettings()

    /**
     * Leave the camera. Photos already taken keep saving; the host pops the screen. A second call
     * does nothing, so a double tap cannot pop the screen underneath too.
     */
    abstract fun close()
}
