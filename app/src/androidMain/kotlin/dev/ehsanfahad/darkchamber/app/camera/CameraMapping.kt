package dev.ehsanfahad.darkchamber.app.camera

import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import java.nio.ByteBuffer

/**
 * The permission as the seam reports it.
 *
 * Android has no "never asked" answer: before any request and after a permanent refusal it says the
 * same thing — not granted, no rationale. So [asked] is whether **this process** has asked; until it
 * has, that ambiguous answer is read as not asked, and the request that follows tells the two apart
 * (the system shows its dialog, or refuses at once). Kept in memory only — nothing about the camera
 * permission is written to disk.
 *
 * [showRationale] is true only after a refusal the system will still ask about again, which is
 * exactly when "Allow" is an offer that does something.
 */
internal fun permissionState(granted: Boolean, asked: Boolean, showRationale: Boolean): CameraPermission =
    when {
        granted -> CameraPermission.Granted
        showRationale -> CameraPermission.Denied
        asked -> CameraPermission.PermanentlyDenied
        else -> CameraPermission.NotAsked
    }

/**
 * Whether a camera can be used, from the error CameraX reports on its state (`null` for none).
 * "In use by another app" only for the one error that says exactly that — not for too many cameras
 * open, which need not be another app's doing. Everything else is unavailable, naming no cause.
 */
internal fun availabilityFor(error: Int?): CameraAvailability =
    when (error) {
        null -> CameraAvailability.Available
        CameraState.ERROR_CAMERA_IN_USE -> CameraAvailability.InUseByAnotherApp
        else -> CameraAvailability.Unavailable
    }

/**
 * The rotation a capture should be taken for, from the phone's tilt as an [OrientationEventListener]
 * reports it — CameraX's documented mapping. `null` when the tilt is unknown (the phone is flat),
 * where the last known rotation should stand.
 */
internal fun surfaceRotation(orientationDegrees: Int): Int? =
    when (orientationDegrees) {
        OrientationEventListener.ORIENTATION_UNKNOWN -> null
        in 45 until 135 -> Surface.ROTATION_270
        in 135 until 225 -> Surface.ROTATION_180
        in 225 until 315 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }

internal fun flashModeFor(mode: FlashMode): Int =
    when (mode) {
        FlashMode.Off -> ImageCapture.FLASH_MODE_OFF
        FlashMode.Auto -> ImageCapture.FLASH_MODE_AUTO
        FlashMode.On -> ImageCapture.FLASH_MODE_ON
    }

/**
 * The JPEG out of the capture's buffer, whole and as a copy: the buffer belongs to the platform and is
 * recycled once the image is closed, and its read position is not ours to trust.
 */
internal fun copyJpeg(buffer: ByteBuffer): ByteArray {
    val source = buffer.duplicate().apply { rewind() }
    return ByteArray(source.remaining()).also { source.get(it) }
}
