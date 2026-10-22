package dev.ehsanfahad.darkchamber.app.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient

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
