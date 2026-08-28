package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError

/**
 * Host test double for [BiometricAuthenticator]. Drives every unlock path on the JVM host and
 * iOS simulator, where no real biometric hardware is reachable.
 *
 * On [Mode.Approve] it echoes the request's `cryptoHandle` back unchanged in [BiometricAuth]
 * (the real actuals return the *authorized* handle; the fake has no crypto object, so it simply
 * propagates whatever was passed — a `null` handle in host tests). The failure modes map to the
 * same typed errors the platform actuals must honour.
 *
 * @param mode which outcome to produce for every [authenticate] call.
 */
class FakeBiometricAuthenticator(
    private val mode: Mode = Mode.Approve,
) : BiometricAuthenticator {
    enum class Mode {
        /** Face/Touch ID succeeds — handle propagated. */
        Approve,

        /** User cancelled / dismissed the prompt. */
        Deny,

        /** Biometrics re-enrolled → key permanently invalidated. */
        Invalidated,
    }

    /** How many times [authenticate] was called (for asserting a prompt was shown exactly once). */
    var authenticateCount: Int = 0
        private set

    override suspend fun authenticate(request: BiometricRequest): Outcome<BiometricAuth> {
        authenticateCount++
        return when (mode) {
            Mode.Approve -> Outcome.Ok(BiometricAuth(request.cryptoHandle))
            Mode.Deny -> Outcome.Err(VaultError.Auth.Cancelled)
            Mode.Invalidated -> Outcome.Err(VaultError.Crypto.IntegrityFailure)
        }
    }
}
