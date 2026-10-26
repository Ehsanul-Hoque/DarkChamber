package dev.ehsanfahad.darkchamber.app.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingEnd
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingFailure
import platform.AVFoundation.AVErrorDiskFull
import platform.AVFoundation.AVErrorMaximumFileSizeReached
import platform.AVFoundation.AVErrorNoDataCaptured
import platform.AVFoundation.AVErrorSessionWasInterrupted
import platform.AVFoundation.AVErrorUnknown
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureSessionInterruptionReasonAudioDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableDueToSystemPressure
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableWithMultipleForegroundApps
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rules inside the AVFoundation seam, out of the framework calls. */
class IosCameraMappingTest {

    /**
     * iOS asks once. A refusal — and a restriction, which the user cannot lift from the prompt
     * either — is final until Settings, so neither is "denied, ask again".
     */
    @Test
    fun the_permission_follows_the_authorization_status() {
        assertEquals(CameraPermission.NotAsked, permissionFor(AVAuthorizationStatusNotDetermined))
        assertEquals(CameraPermission.Granted, permissionFor(AVAuthorizationStatusAuthorized))
        assertEquals(CameraPermission.PermanentlyDenied, permissionFor(AVAuthorizationStatusDenied))
        assertEquals(CameraPermission.PermanentlyDenied, permissionFor(AVAuthorizationStatusRestricted))
        assertEquals(CameraPermission.PermanentlyDenied, permissionFor(99L), "an unknown status grants nothing")
    }

    /** The microphone's answer from the same statuses, into its own type. */
    @Test
    fun the_microphone_permission_follows_the_authorization_status() {
        assertEquals(MicrophonePermission.NotAsked, microphonePermissionFor(AVAuthorizationStatusNotDetermined))
        assertEquals(MicrophonePermission.Granted, microphonePermissionFor(AVAuthorizationStatusAuthorized))
        assertEquals(MicrophonePermission.PermanentlyDenied, microphonePermissionFor(AVAuthorizationStatusDenied))
        assertEquals(MicrophonePermission.PermanentlyDenied, microphonePermissionFor(AVAuthorizationStatusRestricted))
    }

    /**
     * How the movie file output's finish becomes the seam's end. No error is a whole clip; the size
     * limit is named; a full disk is named, because the file may still hold a clip worth one save;
     * no data is no clip. Any other error keeps the clip only when AVFoundation says the recording
     * still finished — an interruption, for one — and is a plain failure otherwise.
     */
    @Test
    fun each_finish_becomes_its_end() {
        assertEquals(RecordingEnd.Finalized, recordingEndFor(errorCode = null, finishedSuccessfully = null))
        assertEquals(
            RecordingEnd.FinalizedAtSizeLimit,
            recordingEndFor(AVErrorMaximumFileSizeReached, finishedSuccessfully = true),
        )
        assertEquals(
            RecordingEnd.Failed(RecordingFailure.StorageFull),
            recordingEndFor(AVErrorDiskFull, finishedSuccessfully = true),
        )
        assertEquals(RecordingEnd.NoFrames, recordingEndFor(AVErrorNoDataCaptured, finishedSuccessfully = false))
        assertEquals(RecordingEnd.Finalized, recordingEndFor(AVErrorSessionWasInterrupted, finishedSuccessfully = true))
        assertEquals(
            RecordingEnd.Failed(RecordingFailure.Other),
            recordingEndFor(AVErrorSessionWasInterrupted, finishedSuccessfully = false),
        )
        assertEquals(RecordingEnd.Failed(RecordingFailure.Other), recordingEndFor(AVErrorUnknown, finishedSuccessfully = null))
    }

    /**
     * The rotation coordinator's horizon-level angle as the seam's tilt. Upright portrait records
     * at 90°, because the sensor is landscape; a phone turned a quarter clockwise records at 180°.
     * Readings between the four are rounded to the nearest.
     */
    @Test
    fun the_capture_angle_becomes_the_tilt() {
        assertEquals(0, tiltForCaptureAngle(90.0))
        assertEquals(90, tiltForCaptureAngle(180.0))
        assertEquals(180, tiltForCaptureAngle(270.0))
        assertEquals(270, tiltForCaptureAngle(0.0))
        assertEquals(0, tiltForCaptureAngle(100.0))
        assertEquals(270, tiltForCaptureAngle(359.0))
    }

    /** "In use by another app" only for the reason that says exactly that. */
    @Test
    fun only_another_client_using_the_camera_is_reported_as_in_use() {
        assertEquals(CameraAvailability.Available, availabilityForInterruption(reason = null))
        assertEquals(
            CameraAvailability.InUseByAnotherApp,
            availabilityForInterruption(AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient),
        )
        for (other in listOf(
            AVCaptureSessionInterruptionReasonAudioDeviceInUseByAnotherClient,
            AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground,
            AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableWithMultipleForegroundApps,
            AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableDueToSystemPressure,
            99L,
        )) {
            assertEquals(CameraAvailability.Unavailable, availabilityForInterruption(other), "reason $other names no cause")
        }
    }
}
