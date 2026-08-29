package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.app.di.CatalogProvider
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.KeyStoreBlob
import dev.ehsanfahad.darkchamber.core.crypto.KeyStoreCodec
import dev.ehsanfahad.darkchamber.core.crypto.SecureKeyStore
import dev.ehsanfahad.darkchamber.core.crypto.Slot
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage

/**
 * The fail-closed cold-open of the vault — the composition root's single entry point.
 *
 * Resolves the app-private, backup-excluded vault root, opens the catalog + storage, and creates or
 * unlocks a [VaultSession]. Three ways in: [createVault] (first run — mints the passphrase + recovery
 * slots), [unlock] (passphrase), and [unlockWithBiometric] (the HARDWARE slot, §3.3). Every step is
 * `Outcome`-typed and the first failure aborts, never a half-initialized vault (D6). No exception
 * crosses out (a catch-all maps an unexpected throw to [VaultError.Unexpected]); failures are logged
 * as a redacted code only by the caller (hard rule #4). The unlocked subtree owns the returned
 * [OpenVault] and its teardown (session zeroize, tmp purge, driver close — TASK-008).
 *
 * Constructed by Koin ([dev.ehsanfahad.darkchamber.app.di.commonAppModule]).
 *
 * @param rootResolver resolves + prepares the backup-excluded vault root.
 * @param engine the sole cryptographic primitive seam (hard rule #2).
 * @param catalogProvider opens the catalog over a resolved root (platform-bound driver creation).
 * @param secureKeyStore the HARDWARE-slot wrap seam (biometric enroll / unlock).
 */
class VaultBootstrap(
    private val rootResolver: VaultRootResolver,
    private val engine: CryptoEngine,
    private val catalogProvider: CatalogProvider,
    private val secureKeyStore: SecureKeyStore,
) {
    /** A live, unlocked vault: the session plus the resources the unlocked subtree owns + tears down. */
    class OpenVault internal constructor(
        val session: VaultSession,
        val storage: VaultStorage,
        val catalog: CatalogStore,
    )

    /** An [OpenVault] plus the one-time recovery-key display string from [createVault]. */
    class CreatedVaultSession internal constructor(
        val vault: OpenVault,
        /** Shown to the user **once**; never stored by us (docs/DESIGN.md §10). */
        val recoveryKey: String,
    )

    /** Whether a vault exists yet, and whether it carries a biometric HARDWARE slot (lock-screen UX). */
    class VaultStatus(val exists: Boolean, val hasHardwareSlot: Boolean)

    /**
     * A lightweight look at the persisted keystore — **no catalog opened** (no driver to leak) — so the
     * lock screen can pick create-vs-unlock and whether to offer biometric. Resolves the root and reads
     * `keys/mk.wrapped`: absent → no vault; present → decode and report the hardware slot.
     */
    fun probe(): Outcome<VaultStatus> {
        return try {
            val root = when (val r = rootResolver.resolve()) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val storage = try {
                VaultStorage(root)
            } catch (e: Exception) {
                return Outcome.Err(VaultError.Io(e))
            }
            val bytes = when (val b = storage.readKeyStore()) {
                is Outcome.Err -> return b
                is Outcome.Ok -> b.value
            } ?: return Outcome.Ok(VaultStatus(exists = false, hasHardwareSlot = false))
            when (val blob = KeyStoreCodec.decode(bytes)) {
                is Outcome.Err -> blob
                is Outcome.Ok -> Outcome.Ok(VaultStatus(exists = true, hasHardwareSlot = blob.value.hardwareWrap != null))
            }
        } catch (e: Exception) {
            Outcome.Err(VaultError.Unexpected(e))
        }
    }

    /**
     * First run: create the vault (random MK wrapped by [passphrase] + a fresh recovery key), persist
     * its keystore, and return the live session plus the recovery-key display string. Refuses if a
     * keystore already exists (never clobbers an existing vault). [passphrase] is consumed (zeroized)
     * here whether or not the create succeeds (hard rule #5).
     */
    fun createVault(passphrase: ByteArray): Outcome<CreatedVaultSession> {
        try {
            val res = when (val r = openResources()) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val existing = when (val e = res.storage.readKeyStore()) {
                is Outcome.Err -> return e
                is Outcome.Ok -> e.value
            }
            if (existing != null) {
                // A vault is already here — the caller should have taken the unlock path.
                return Outcome.Err(VaultError.Unexpected(IllegalStateException("vault already exists")))
            }
            val created = VaultSession.createVault(engine, DefaultKeyDerivation(engine), passphrase)
            when (val w = res.storage.writeKeyStore(KeyStoreCodec.encode(created.keyStore))) {
                is Outcome.Err -> {
                    created.session.close()
                    return w
                }
                is Outcome.Ok -> Unit
            }
            val vault = OpenVault(created.session, res.storage, res.catalog)
            return Outcome.Ok(CreatedVaultSession(vault, created.recoveryKey))
        } catch (e: Exception) {
            return Outcome.Err(VaultError.Unexpected(e))
        } finally {
            passphrase.fill(0)
        }
    }

    /**
     * Reopen an existing vault by unlocking the PASSPHRASE slot. A wrong passphrase → `IntegrityFailure`;
     * no keystore present → `Unexpected` (the caller should have taken the create path). [passphrase]
     * is consumed (zeroized).
     */
    fun unlock(passphrase: ByteArray): Outcome<OpenVault> {
        try {
            val res = when (val r = openResources()) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val blob = when (val b = readKeyStore(res.storage)) {
                is Outcome.Err -> return b
                is Outcome.Ok -> b.value
            }
            return when (val s = VaultSession.unlock(engine, DefaultKeyDerivation(engine), blob, passphrase, Slot.PASSPHRASE)) {
                is Outcome.Err -> s
                is Outcome.Ok -> Outcome.Ok(OpenVault(s.value, res.storage, res.catalog))
            }
        } catch (e: Exception) {
            return Outcome.Err(VaultError.Unexpected(e))
        } finally {
            passphrase.fill(0)
        }
    }

    /**
     * Reopen an existing vault via the HARDWARE slot, prompting for biometrics through [authenticator].
     * A cancelled prompt → `Auth`; a re-enrolled/invalidated key → `IntegrityFailure` (the UI then
     * forces the passphrase, §3.3); no hardware slot → `MalformedContainer`.
     */
    suspend fun unlockWithBiometric(authenticator: BiometricAuthenticator): Outcome<OpenVault> {
        return try {
            val res = when (val r = openResources()) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val blob = when (val b = readKeyStore(res.storage)) {
                is Outcome.Err -> return b
                is Outcome.Ok -> b.value
            }
            when (val s = VaultSession.unlockWithHardware(engine, blob, secureKeyStore, authenticator)) {
                is Outcome.Err -> s
                is Outcome.Ok -> Outcome.Ok(OpenVault(s.value, res.storage, res.catalog))
            }
        } catch (e: Exception) {
            Outcome.Err(VaultError.Unexpected(e))
        }
    }

    /**
     * Enroll the HARDWARE slot on an already-unlocked [vault] (the "enable biometric" step): wrap MK
     * under the platform key (prompting through [authenticator]) and persist the updated keystore. A
     * cancelled prompt leaves the keystore unchanged.
     */
    suspend fun enrollBiometric(vault: OpenVault, authenticator: BiometricAuthenticator): Outcome<Unit> {
        return try {
            val blob = when (val b = readKeyStore(vault.storage)) {
                is Outcome.Err -> return b
                is Outcome.Ok -> b.value
            }
            val updated = when (val u = vault.session.addHardwareSlot(blob, secureKeyStore, authenticator)) {
                is Outcome.Err -> return u
                is Outcome.Ok -> u.value
            }
            vault.storage.writeKeyStore(KeyStoreCodec.encode(updated))
        } catch (e: Exception) {
            Outcome.Err(VaultError.Unexpected(e))
        }
    }

    private class Resources(val storage: VaultStorage, val catalog: CatalogStore)

    /** Resolve the root and open storage + catalog (no session yet). Fail-closed: first Err aborts. */
    private fun openResources(): Outcome<Resources> {
        val root = when (val r = rootResolver.resolve()) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
        val storage = try {
            VaultStorage(root)
        } catch (e: Exception) {
            return Outcome.Err(VaultError.Io(e))
        }
        return when (val c = catalogProvider.open(root)) {
            is Outcome.Err -> c
            is Outcome.Ok -> Outcome.Ok(Resources(storage, c.value))
        }
    }

    /** Read + decode the persisted keystore; a missing keystore is `Unexpected` (no vault to unlock). */
    private fun readKeyStore(storage: VaultStorage): Outcome<KeyStoreBlob> {
        val bytes = when (val r = storage.readKeyStore()) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        } ?: return Outcome.Err(VaultError.Unexpected(IllegalStateException("no vault to unlock")))
        return KeyStoreCodec.decode(bytes)
    }
}
