package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.SecureKeyStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.flatMap

/**
 * In-memory [SecureKeyStore] stand-in for host/sim tests (no Keystore/Keychain, no real biometric).
 * `wrap`/`unwrap` are an identity round-trip over a copy — enough to exercise the bootstrap graph
 * and the HARDWARE-slot path without a real platform key. The biometric prompt is threaded through
 * [authenticator] so a denying/invalidating fake propagates the same error the real actuals would.
 */
class FakeSecureKeyStore : SecureKeyStore {
    override fun isAvailable(): Boolean = true

    override suspend fun wrap(mk: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> =
        authenticator.authenticate(AUTH_REQUEST).flatMap { Outcome.Ok(mk.copyOf()) }

    override suspend fun unwrap(blob: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> =
        authenticator.authenticate(AUTH_REQUEST).flatMap { Outcome.Ok(blob.copyOf()) }

    override fun deleteHardwareKey() = Unit

    private companion object {
        val AUTH_REQUEST = dev.ehsanfahad.darkchamber.core.crypto.BiometricRequest(
            title = "Unlock vault",
            cancelLabel = "Cancel",
        )
    }
}
