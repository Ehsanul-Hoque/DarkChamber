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
    private val usable: Boolean = true,
) : BiometricAuthenticator {
    /** [NotUsable]: the platform key could not be used this time, with nothing saying it is gone. */
    enum class Mode { Approve, Deny, Invalidated, NotUsable }

    override fun canAuthenticate(): Boolean = usable

    override suspend fun authenticate(request: BiometricRequest): Outcome<BiometricAuth> =
        when (mode) {
            Mode.Approve -> Outcome.Ok(BiometricAuth(request.cryptoHandle))
            Mode.Deny -> Outcome.Err(VaultError.Auth.Cancelled)
            Mode.Invalidated -> Outcome.Err(VaultError.Crypto.IntegrityFailure)
            Mode.NotUsable -> Outcome.Err(VaultError.Auth.Failed)
        }
}
