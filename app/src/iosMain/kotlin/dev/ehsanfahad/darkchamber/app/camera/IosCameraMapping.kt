package dev.ehsanfahad.darkchamber.app.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingEnd
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingFailure
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
import platform.AVFoundation.AVErrorDiskFull
import platform.AVFoundation.AVErrorMaximumFileSizeReached
import platform.AVFoundation.AVErrorNoDataCaptured
import kotlin.math.roundToInt

/**
 * The permission as the seam reports it. iOS asks exactly once, so every refusal is permanent until
 * Settings — and a restriction (parental controls, a managed device) cannot be lifted from a prompt
 * either. Anything unrecognised grants nothing.
 */
internal fun permissionFor(status: AVAuthorizationStatus): CameraPermission =
    when (status) {
        AVAuthorizationStatusNotDetermined -> CameraPermission.NotAsked
        AVAuthorizationStatusAuthorized -> CameraPermission.Granted
        else -> CameraPermission.PermanentlyDenied
    }

/** The microphone's answer, read exactly as the camera's is — see [permissionFor]. */
internal fun microphonePermissionFor(status: AVAuthorizationStatus): MicrophonePermission =
    when (permissionFor(status)) {
        CameraPermission.NotAsked -> MicrophonePermission.NotAsked
        CameraPermission.Granted -> MicrophonePermission.Granted
        CameraPermission.Denied -> MicrophonePermission.Denied
        CameraPermission.PermanentlyDenied -> MicrophonePermission.PermanentlyDenied
    }

/**
 * How a recording ended, from the error the movie file output finished with (`null` for none) and
 * its `AVErrorRecordingSuccessfullyFinishedKey` — AVFoundation's own word on whether the file holds
 * a usable clip despite the error.
 *
 * The size limit, a full disk and no data are named, as on Android. Any other error — an
 * interruption, a session that stopped, a device that went away — keeps the clip only when
 * AVFoundation says the recording still finished, and names no cause otherwise.
 */
internal fun recordingEndFor(errorCode: Long?, finishedSuccessfully: Boolean?): RecordingEnd =
    when (errorCode) {
        null -> RecordingEnd.Finalized
        AVErrorMaximumFileSizeReached -> RecordingEnd.FinalizedAtSizeLimit
        AVErrorDiskFull -> RecordingEnd.Failed(RecordingFailure.StorageFull)
        AVErrorNoDataCaptured -> RecordingEnd.NoFrames
        else -> if (finishedSuccessfully == true) RecordingEnd.Finalized else RecordingEnd.Failed(RecordingFailure.Other)
    }

/**
 * The seam's tilt — how far the phone is turned clockwise from upright — from the rotation
 * coordinator's horizon-level capture angle. The sensor is landscape, so upright portrait captures
 * at 90°; the angle grows as the phone turns clockwise. Rounded to the nearest quarter turn.
 */
internal fun tiltForCaptureAngle(angleDegrees: Double): Int {
    val quarter = ((angleDegrees / 90.0).roundToInt() % 4 + 4) % 4
    return ((quarter - 1 + 4) % 4) * 90
}

/**
 * Whether the camera can be used, from the reason a capture session was interrupted (`null` once it
 * is not). "In use by another app" only for the one reason that says so; every other reason —
 * backgrounded, Split View, system pressure, an unknown one — is unavailable, naming no cause.
 */
internal fun availabilityForInterruption(reason: Long?): CameraAvailability =
    when (reason) {
        null -> CameraAvailability.Available
        AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient -> CameraAvailability.InUseByAnotherApp
        else -> CameraAvailability.Unavailable
    }
