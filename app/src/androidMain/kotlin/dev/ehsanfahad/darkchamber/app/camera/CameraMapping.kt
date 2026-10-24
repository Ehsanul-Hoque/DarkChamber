package dev.ehsanfahad.darkchamber.app.camera

import android.view.OrientationEventListener
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
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.nanoseconds

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

/** The microphone's answer, read exactly as the camera's is — see [permissionState]. */
internal fun microphonePermissionState(granted: Boolean, asked: Boolean, showRationale: Boolean): MicrophonePermission =
    when (permissionState(granted, asked, showRationale)) {
        CameraPermission.Granted -> MicrophonePermission.Granted
        CameraPermission.Denied -> MicrophonePermission.Denied
        CameraPermission.PermanentlyDenied -> MicrophonePermission.PermanentlyDenied
        CameraPermission.NotAsked -> MicrophonePermission.NotAsked
    }

/**
 * How a recording ended, from the error CameraX finalized it with.
 *
 * Kept as a whole clip: no error; a duration limit (none is set, but CameraX saves what came before
 * one); and the camera closing under the recording, which CameraX finalizes with the frames it had.
 * Our own teardown never reaches this — it reports the recording abandoned before it releases the
 * camera, and the first end reported is the one that stands.
 *
 * Full storage is named, because CameraX may still have written a playable file. Every error after
 * which CameraX says the file is not to be trusted, and any code it adds later, names no cause.
 */
internal fun recordingEndFor(error: Int): RecordingEnd =
    when (error) {
        VideoRecordEvent.Finalize.ERROR_NONE,
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
        -> RecordingEnd.Finalized
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> RecordingEnd.FinalizedAtSizeLimit
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> RecordingEnd.Failed(RecordingFailure.StorageFull)
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> RecordingEnd.NoFrames
        else -> RecordingEnd.Failed(RecordingFailure.Other)
    }

/** CameraX's running count, in the seam's terms. */
internal fun recordingProgressFor(bytesRecorded: Long, recordedDurationNanos: Long): RecordingProgress =
    RecordingProgress(bytesRecorded, recordedDurationNanos.nanoseconds)

/**
 * The seam's tilt — how far the phone is turned clockwise from upright — from the rotation a capture
 * is taken for ([surfaceRotation]), which counts the other way round.
 */
internal fun tiltDegrees(surfaceRotation: Int): Int =
    when (surfaceRotation) {
        Surface.ROTATION_270 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_90 -> 270
        else -> 0
    }

/** The shape each mode captures at, which the preview is given too so it shows the saved frame. */
internal fun aspectRatioFor(mode: CaptureMode): Int =
    when (mode) {
        CaptureMode.Photo -> AspectRatio.RATIO_4_3
        CaptureMode.Video -> AspectRatio.RATIO_16_9
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
