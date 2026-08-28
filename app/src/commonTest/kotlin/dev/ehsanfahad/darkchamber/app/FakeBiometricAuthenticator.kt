package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuth
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.BiometricRequest
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError

/**
 * :app-side [BiometricAuthenticator] stand-in for host/sim bootstrap tests (core:crypto's own fake
 * lives in its test source set, not visible here — as with [FakeSecureKeyStore]). Approve echoes the
 * request handle; Deny maps to [VaultError.Auth.Cancelled].
 */
class FakeBiometricAuthenticator(
    private val mode: Mode = Mode.Approve,
) : BiometricAuthenticator {
    enum class Mode { Approve, Deny }

    override suspend fun authenticate(request: BiometricRequest): Outcome<BiometricAuth> =
        when (mode) {
            Mode.Approve -> Outcome.Ok(BiometricAuth(request.cryptoHandle))
            Mode.Deny -> Outcome.Err(VaultError.Auth.Cancelled)
        }
}
