package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.core.crypto.SecureKeyStore
import dev.ehsanfahad.darkchamber.core.model.Outcome

/**
 * In-memory [SecureKeyStore] stand-in for host/sim tests (no Keystore/Keychain, no biometric).
 * `wrap`/`unwrap` are an identity round-trip over a copy — enough to exercise the bootstrap graph
 * and the HARDWARE-slot path without a real platform key.
 */
class FakeSecureKeyStore : SecureKeyStore {
    override fun isAvailable(): Boolean = true

    override fun wrap(mk: ByteArray): ByteArray = mk.copyOf()

    override fun unwrap(blob: ByteArray): Outcome<ByteArray> = Outcome.Ok(blob.copyOf())

    override fun deleteHardwareKey() = Unit
}
