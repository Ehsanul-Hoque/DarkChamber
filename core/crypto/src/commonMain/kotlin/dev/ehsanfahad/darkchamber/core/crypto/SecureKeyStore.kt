package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome

/**
 * The seam for the HARDWARE MK-wrap slot (FORMAT.md §3.1). Wraps the MasterKey under a
 * platform-held, hardware-backed key that never leaves the device's secure hardware — Android
 * Keystore/StrongBox, iOS Keychain — and hands back an **opaque blob** to store in the KeyStore
 * blob's `hardwareWrap` field. The blob is not a KDF wrap: no `kdfId`/`kdfParams` apply, and its
 * internal structure (Android `iv ‖ ciphertext`, iOS Keychain payload) belongs to the platform.
 *
 * This is a plain interface, deliberately **not** `expect`/`actual`: the platform implementations
 * are stateful and need constructor arguments (Android `Context` + key alias; iOS Keychain service
 * string), so the app composition root constructs the right one and injects it (a later cycle).
 * `commonMain` and [VaultSession] depend only on this interface, which lets `FakeSecureKeyStore`
 * exercise every HARDWARE-slot integration path on the JVM host.
 *
 * It is key **storage**, not content crypto: because a non-exportable TEE key cannot be handed to
 * [CryptoEngine] (which takes raw key bytes), the platform actuals are the sanctioned hard-rule-#2
 * carve-out that may import the platform crypto APIs (`java.security`/`javax.crypto`,
 * `platform.Security`) — confined to those two files.
 *
 * In this cycle the platform key is gated on **device-unlocked**; per-use biometric prompting is
 * added with the UI cycle.
 */
interface SecureKeyStore {
    /** True if this device can host a hardware-backed key (and one may be created). */
    fun isAvailable(): Boolean

    /** Wrap [mk] under the platform key, returning an opaque blob to store in the HARDWARE slot. */
    fun wrap(mk: ByteArray): ByteArray

    /**
     * Recover MK from a [blob] produced by [wrap]. Auth failure, a deleted/invalidated key, or any
     * tamper yields [Outcome.Err] of
     * [dev.ehsanfahad.darkchamber.core.model.VaultError.Crypto.IntegrityFailure] — never a throw
     * across the boundary, never a partial result.
     */
    fun unwrap(blob: ByteArray): Outcome<ByteArray>

    /** Destroy the platform key (the panic-wipe primitive); idempotent — safe if none exists. */
    fun deleteHardwareKey()
}
