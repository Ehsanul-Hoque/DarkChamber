package dev.ehsanfahad.darkchamber.app.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
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
     * either — is final until Settings, so neither is "denied, ask again" (spec D8).
     */
    @Test
    fun the_permission_follows_the_authorization_status() {
        assertEquals(CameraPermission.NotAsked, permissionFor(AVAuthorizationStatusNotDetermined))
        assertEquals(CameraPermission.Granted, permissionFor(AVAuthorizationStatusAuthorized))
        assertEquals(CameraPermission.PermanentlyDenied, permissionFor(AVAuthorizationStatusDenied))
        assertEquals(CameraPermission.PermanentlyDenied, permissionFor(AVAuthorizationStatusRestricted))
        assertEquals(CameraPermission.PermanentlyDenied, permissionFor(99L), "an unknown status grants nothing")
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
