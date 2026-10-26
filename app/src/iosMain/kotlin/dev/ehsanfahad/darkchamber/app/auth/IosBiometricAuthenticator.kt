package dev.ehsanfahad.darkchamber.app.auth

import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuth
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.BiometricRequest
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

/**
 * iOS [BiometricAuthenticator] actual: evaluates a fresh `LAContext` against the biometric policy and,
 * on success, returns that **evaluated** context. `IosSecureKeyStore` threads it into the Keychain
 * query (`kSecUseAuthenticationContext`) so the item — gated by a `.biometryCurrentSet`
 * `SecAccessControl` — is released by this single Face/Touch ID. `LocalAuthentication` is device auth,
 * not a cipher primitive (hard rule #2).
 */
class IosBiometricAuthenticator : BiometricAuthenticator {

    // The policy the prompt evaluates: false with no Face/Touch ID, none enrolled, or locked out.
    @OptIn(ExperimentalForeignApi::class)
    override fun canAuthenticate(): Boolean =
        LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)

    override suspend fun authenticate(request: BiometricRequest): Outcome<BiometricAuth> =
        suspendCancellableCoroutine { cont ->
            val context = LAContext()
            context.evaluatePolicy(
                policy = LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                localizedReason = request.subtitle ?: request.title,
            ) { success, error ->
                if (!cont.isActive) return@evaluatePolicy
                if (success) {
                    cont.resume(Outcome.Ok(BiometricAuth(context)))
                } else {
                    cont.resume(Outcome.Err(mapError(error)))
                }
            }
        }

    private fun mapError(error: NSError?): VaultError = when (error?.code) {
        LAErrorUserCancel, LAErrorSystemCancel, LAErrorAppCancel, LAErrorUserFallback ->
            VaultError.Auth.Cancelled

        LAErrorBiometryNotAvailable, LAErrorBiometryNotEnrolled, LAErrorBiometryLockout ->
            VaultError.Auth.Unavailable

        else -> VaultError.Auth.Cancelled
    }
}
