package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome

/**
 * The seam for a per-use biometric prompt. Presents Face/Touch ID (Android
 * `BiometricPrompt`, iOS `LAContext`) and, on success, returns an **authenticated handle**
 * the [SecureKeyStore] actual uses to complete the wrapped-MK crypto op — so the biometric
 * is bound *cryptographically* to unwrapping the key, not merely a UI check in front of it.
 *
 * Like [SecureKeyStore], this is a plain injected interface, deliberately **not**
 * `expect`/`actual`: the platform implementations are stateful (Android needs the current
 * `FragmentActivity`; iOS an `LAContext`) and are constructed by the app composition root.
 * `commonMain` and [VaultSession] depend only on this interface, which lets
 * `FakeBiometricAuthenticator` drive every unlock path on the JVM host and iOS simulator.
 *
 * The handle types are platform-opaque and never cross into `commonMain` logic as anything
 * but `Any?`; only the platform [SecureKeyStore] actual (the sanctioned hard-rule-#2 carve-out)
 * knows their real type (Android authorized `CryptoObject`, iOS evaluated `LAContext`).
 */
interface BiometricAuthenticator {
    /**
     * Prompt for biometric auth. On success returns a [BiometricAuth] carrying the authenticated
     * handle; on user-cancel / no-enrollment returns [Outcome.Err] of
     * [dev.ehsanfahad.darkchamber.core.model.VaultError.Auth]; on a permanently invalidated key
     * (biometrics re-enrolled) returns [Outcome.Err] of `Crypto.IntegrityFailure`. Never throws
     * across the boundary.
     */
    suspend fun authenticate(request: BiometricRequest): Outcome<BiometricAuth>
}

/**
 * A biometric-prompt request. The strings are UI copy (honest, per docs/DESIGN.md §11 — never a
 * promise beyond THREAT_MODEL). [cryptoHandle] is the platform crypto object to authorize:
 * an Android `BiometricPrompt.CryptoObject` wrapping the unwrap `Cipher`, or `null` on iOS
 * (where the `LAContext` itself gates the Keychain item). It is `Any?` because `commonMain`
 * must not import a platform cipher type (hard rule #2).
 */
data class BiometricRequest(
    val title: String,
    val subtitle: String? = null,
    val cancelLabel: String,
    val cryptoHandle: Any? = null,
)

/**
 * The result of a successful [BiometricAuthenticator.authenticate]. [handle] is the authenticated,
 * platform-opaque object the [SecureKeyStore] actual completes the crypto op with — the authorized
 * `CryptoObject` (Android) or the evaluated `LAContext` (iOS); `null` for the host fake.
 */
data class BiometricAuth(val handle: Any?)
