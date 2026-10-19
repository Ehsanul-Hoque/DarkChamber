package dev.ehsanfahad.darkchamber.feature.camera.presentation

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureProblem
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureQueueState
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationInfo
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationTitle

/**
 * Everything the camera screen draws from, and every decision about it.
 *
 * The decisions live here as properties rather than in the composable because there are no UI
 * tests: a rule written inside a layout is a rule nothing asserts. The screen reads these and draws.
 *
 * Holds ids, counts and enums only — the newest photo is [CaptureQueueState.lastSavedId], never its
 * bytes. The one piece of decrypted content is the album's name inside [destination], and its
 * `toString` does not print it.
 */
internal class CameraState(
    /** `null` until the destination's first emission. */
    val destination: DestinationInfo?,
    val permission: CameraPermission,
    val availability: CameraAvailability,
    val capabilities: CameraCapabilities,
    val queue: CaptureQueueState,
    /** The lens, flash setting and zoom last asked of the camera — what the controls draw. */
    val lens: Lens = Lens.Back,
    val flash: FlashMode = FlashMode.Off,
    val zoom: Float = 1f,
) {
    private val albumGone: Boolean get() = destination?.title == DestinationTitle.AlbumGone

    /**
     * Whether a press would take a photo. A destination still loading does not block it — the
     * import checks the album itself, so a photo in that first moment is filed or fails honestly.
     * An album known to be gone does: every photo would fail.
     */
    val shutterEnabled: Boolean
        get() = permission == CameraPermission.Granted &&
            availability == CameraAvailability.Available &&
            queue.shutterEnabled &&
            !albumGone

    /**
     * What stands in place of the viewfinder, if anything. Permission speaks first: whether a
     * camera is available means nothing until we may open one. Not asked yet shows nothing — the
     * system's own prompt is up.
     */
    val explainer: CameraExplainer
        get() = when (permission) {
            CameraPermission.NotAsked -> CameraExplainer.None
            CameraPermission.Denied -> CameraExplainer.Denied
            CameraPermission.PermanentlyDenied -> CameraExplainer.PermanentlyDenied
            CameraPermission.Granted -> when (availability) {
                CameraAvailability.Available -> CameraExplainer.None
                CameraAvailability.InUseByAnotherApp -> CameraExplainer.InUse
                CameraAvailability.Unavailable -> CameraExplainer.Unavailable
            }
        }

    /** The destination's item count, or `null` when it is not known — never a stand-in zero. */
    val count: Long? get() = destination?.count

    /**
     * The one-line message under the viewfinder. An album that is gone outranks a single failed
     * photo, because it explains every photo after it too.
     */
    val message: CameraMessage?
        get() = when {
            albumGone -> CameraMessage.AlbumGone
            queue.problem == CaptureProblem.SaveFailed -> CameraMessage.SaveFailed
            queue.problem == CaptureProblem.CaptureFailed -> CameraMessage.CaptureFailed
            else -> null
        }

    /** A flip control only when there is another lens to flip to. */
    val flipOffered: Boolean get() = capabilities.lenses.size > 1

    /** A flash control only when the lens in use has a flash. */
    val flashOffered: Boolean get() = capabilities.hasFlash

    override fun toString(): String =
        "CameraState(destination=$destination, permission=$permission, availability=$availability, " +
            "queue=$queue, lens=$lens, flash=$flash, zoom=$zoom)"
}

/** What replaces the viewfinder when the camera cannot be shown. */
internal enum class CameraExplainer { None, Denied, PermanentlyDenied, InUse, Unavailable }

/** The one-line message the screen shows, most important first. */
internal enum class CameraMessage { AlbumGone, SaveFailed, CaptureFailed }
