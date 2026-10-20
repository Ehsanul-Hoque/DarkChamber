package dev.ehsanfahad.darkchamber.app.camera

import android.view.Surface
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The rules inside the CameraX seam, taken out of the framework calls because a host test cannot
 * build a `Context`, a camera or an `ImageProxy`. What is left in the controller is plumbing.
 */
class CameraMappingTest {

    /**
     * Android never says "not asked yet", so the seam keeps whether it has asked in this process and
     * reads the rationale flag, which is true only after a refusal the system will still re-ask.
     */
    @Test
    fun the_permission_state_comes_from_grant_asked_and_rationale() {
        assertEquals(CameraPermission.Granted, permissionState(granted = true, asked = false, showRationale = false))
        assertEquals(CameraPermission.Granted, permissionState(granted = true, asked = true, showRationale = true))
        assertEquals(CameraPermission.NotAsked, permissionState(granted = false, asked = false, showRationale = false))
        // A refusal from an earlier run: the system will ask again, so "Allow" is the true offer.
        assertEquals(CameraPermission.Denied, permissionState(granted = false, asked = false, showRationale = true))
        assertEquals(CameraPermission.Denied, permissionState(granted = false, asked = true, showRationale = true))
        // Asked, refused, and no rationale: the system will not ask again — only Settings can.
        assertEquals(
            CameraPermission.PermanentlyDenied,
            permissionState(granted = false, asked = true, showRationale = false),
        )
    }

    /** "In use by another app" only when the platform says exactly that (spec D8). */
    @Test
    fun only_camera_in_use_is_reported_as_in_use() {
        assertEquals(CameraAvailability.Available, availabilityFor(error = null))
        assertEquals(CameraAvailability.InUseByAnotherApp, availabilityFor(CameraState.ERROR_CAMERA_IN_USE))
        for (other in listOf(
            CameraState.ERROR_MAX_CAMERAS_IN_USE,
            CameraState.ERROR_CAMERA_DISABLED,
            CameraState.ERROR_CAMERA_FATAL_ERROR,
            CameraState.ERROR_STREAM_CONFIG,
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR,
            CameraState.ERROR_CAMERA_REMOVED,
            12345,
        )) {
            assertEquals(CameraAvailability.Unavailable, availabilityFor(other), "error $other names no cause")
        }
    }

    /**
     * The phone's tilt as the rotation a capture is taken for — so a photo taken in landscape is
     * tagged landscape even when the screen did not rotate. Turning the phone clockwise turns the
     * display the other way, hence 90° of tilt is ROTATION_270.
     */
    @Test
    fun the_tilt_becomes_the_capture_rotation() {
        assertEquals(null, surfaceRotation(-1), "flat on a table: no answer, keep the last")
        for (d in listOf(0, 30, 44, 315, 350, 359)) assertEquals(Surface.ROTATION_0, surfaceRotation(d), "$d°")
        for (d in listOf(45, 90, 134)) assertEquals(Surface.ROTATION_270, surfaceRotation(d), "$d°")
        for (d in listOf(135, 180, 224)) assertEquals(Surface.ROTATION_180, surfaceRotation(d), "$d°")
        for (d in listOf(225, 270, 314)) assertEquals(Surface.ROTATION_90, surfaceRotation(d), "$d°")
    }

    @Test
    fun each_flash_setting_maps_to_its_own_mode() {
        assertEquals(
            listOf(ImageCapture.FLASH_MODE_OFF, ImageCapture.FLASH_MODE_AUTO, ImageCapture.FLASH_MODE_ON),
            listOf(FlashMode.Off, FlashMode.Auto, FlashMode.On).map(::flashModeFor),
        )
    }

    /** The whole JPEG, wherever the buffer's read position was left. */
    @Test
    fun the_jpeg_is_copied_whole() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = ByteBuffer.wrap(bytes).apply { position(3) }

        val copy = copyJpeg(buffer)

        assertContentEquals(bytes, copy)
        bytes[0] = 9
        assertEquals(1, copy[0], "a copy, not a view over the platform's buffer")
    }
}
