package dev.ehsanfahad.darkchamber.app.camera

import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.video.VideoRecordEvent
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingEnd
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingFailure
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingProgress
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

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

    /** "In use by another app" only when the platform says exactly that. */
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

    /**
     * The microphone's answer comes from the same three facts as the camera's, and into its own type
     * so the two cannot be crossed.
     */
    @Test
    fun the_microphone_state_comes_from_grant_asked_and_rationale() {
        assertEquals(MicrophonePermission.Granted, microphonePermissionState(granted = true, asked = true, showRationale = false))
        assertEquals(MicrophonePermission.NotAsked, microphonePermissionState(granted = false, asked = false, showRationale = false))
        assertEquals(MicrophonePermission.Denied, microphonePermissionState(granted = false, asked = true, showRationale = true))
        assertEquals(
            MicrophonePermission.PermanentlyDenied,
            microphonePermissionState(granted = false, asked = true, showRationale = false),
        )
    }

    /**
     * How CameraX's finalize error becomes the seam's end. A clip CameraX says it wrote whole is
     * kept — including one cut short because the camera closed under it, which CameraX finalizes
     * with what it had. Full storage is named, because the file may still be worth one save. No data
     * means no clip. Every error after which CameraX says the file is not to be trusted, and any
     * code it adds later, is a plain failure.
     */
    @Test
    fun each_finalize_error_becomes_its_end() {
        assertEquals(RecordingEnd.Finalized, recordingEndFor(VideoRecordEvent.Finalize.ERROR_NONE))
        assertEquals(RecordingEnd.Finalized, recordingEndFor(VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE))
        assertEquals(RecordingEnd.Finalized, recordingEndFor(VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED))
        assertEquals(
            RecordingEnd.FinalizedAtSizeLimit,
            recordingEndFor(VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED),
        )
        assertEquals(
            RecordingEnd.Failed(RecordingFailure.StorageFull),
            recordingEndFor(VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE),
        )
        assertEquals(RecordingEnd.NoFrames, recordingEndFor(VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA))
        for (other in listOf(
            VideoRecordEvent.Finalize.ERROR_UNKNOWN,
            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS,
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED,
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR,
            VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED,
            12345,
        )) {
            assertEquals(RecordingEnd.Failed(RecordingFailure.Other), recordingEndFor(other), "error $other")
        }
    }

    @Test
    fun the_platform_count_becomes_progress() {
        assertEquals(
            RecordingProgress(bytesRecorded = 2_500_000, duration = 1_500.milliseconds),
            recordingProgressFor(bytesRecorded = 2_500_000, recordedDurationNanos = 1_500_000_000),
        )
    }

    /**
     * What a lens is offered at, from whether it records 1080p and 2160p. Each is offered on its
     * own; a lens with neither offers no video quality at all. (Given as two facts because CameraX's
     * `Quality` cannot be loaded off a device.)
     */
    @Test
    fun the_lens_qualities_become_the_offered_ones() {
        assertEquals(setOf(VideoQuality.HD, VideoQuality.UHD), videoQualitiesFor(fullHd = true, ultraHd = true))
        assertEquals(setOf(VideoQuality.HD), videoQualitiesFor(fullHd = true, ultraHd = false))
        assertEquals(setOf(VideoQuality.UHD), videoQualitiesFor(fullHd = false, ultraHd = true))
        assertEquals(emptySet<VideoQuality>(), videoQualitiesFor(fullHd = false, ultraHd = false))
    }

    /**
     * The capture rotation as the seam's tilt: how far the phone is turned clockwise from upright.
     * The display's rotation runs the other way, so ROTATION_270 is a phone turned 90° clockwise.
     */
    @Test
    fun the_capture_rotation_becomes_the_tilt() {
        assertEquals(0, tiltDegrees(Surface.ROTATION_0))
        assertEquals(90, tiltDegrees(Surface.ROTATION_270))
        assertEquals(180, tiltDegrees(Surface.ROTATION_180))
        assertEquals(270, tiltDegrees(Surface.ROTATION_90))
    }

    /** Each mode's shape, so the preview shows the frame the capture saves. */
    @Test
    fun each_mode_has_its_own_aspect_ratio() {
        assertEquals(AspectRatio.RATIO_4_3, aspectRatioFor(CaptureMode.Photo))
        assertEquals(AspectRatio.RATIO_16_9, aspectRatioFor(CaptureMode.Video))
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
