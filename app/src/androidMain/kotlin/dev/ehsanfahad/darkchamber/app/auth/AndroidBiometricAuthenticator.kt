package dev.ehsanfahad.darkchamber.app.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuth
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.BiometricRequest
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android [BiometricAuthenticator] actual: presents a Class-3 (`BIOMETRIC_STRONG`) `BiometricPrompt`
 * over the current `FragmentActivity` and authenticates the request's `CryptoObject` (built by
 * `AndroidSecureKeyStore`). On success the **authorised** `CryptoObject` is returned so the keystore
 * completes the wrapped-MK crypto op. This class only *presents* the prompt — it never touches a
 * `Cipher` (hard rule #2: no cipher primitive in `:app`).
 *
 * The `FragmentActivity` is read from [activityProvider] at prompt time (no static reference).
 */
class AndroidBiometricAuthenticator(
    private val activityProvider: () -> FragmentActivity?,
) : BiometricAuthenticator {

    override suspend fun authenticate(request: BiometricRequest): Outcome<BiometricAuth> {
        val activity = activityProvider()
            ?: return Outcome.Err(VaultError.Auth.Unavailable) // no foreground Activity to host the prompt
        val cryptoObject = request.cryptoHandle as? BiometricPrompt.CryptoObject
            ?: return Outcome.Err(VaultError.Auth.Unavailable)

        return suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!cont.isActive) return
                    val authorized = result.cryptoObject
                    cont.resume(
                        if (authorized != null) {
                            Outcome.Ok(BiometricAuth(authorized))
                        } else {
                            // A Class-3 CryptoObject prompt must return one; absence is a failure.
                            Outcome.Err(VaultError.Crypto.IntegrityFailure)
                        },
                    )
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(Outcome.Err(mapError(errorCode)))
                }

                override fun onAuthenticationFailed() {
                    // Non-terminal (a fingerprint didn't match) — the prompt stays up; do not resume.
                }
            }
            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(request.title)
                .apply { request.subtitle?.let { setSubtitle(it) } }
                .setNegativeButtonText(request.cancelLabel)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            // BiometricPrompt must be driven on the main thread — the main executor guarantees it.
            executor.execute { prompt.authenticate(info, cryptoObject) }
        }
    }

    private fun mapError(code: Int): VaultError = when (code) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_CANCELED,
        -> VaultError.Auth.Cancelled

        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        -> VaultError.Auth.Unavailable

        else -> VaultError.Auth.Cancelled
    }
}
