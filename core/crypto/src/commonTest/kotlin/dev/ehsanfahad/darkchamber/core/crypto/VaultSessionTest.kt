package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultSessionTest {
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)

    // A fresh passphrase array each call — createVault/unlock consume (zeroize) the ones they take.
    private fun pw() = "correct horse battery staple".encodeToByteArray()

    // Cheap Argon2id for tests (m=256 KiB, t=1, p=1); the production m=64 MiB/t=3/p=2 default is
    // asserted separately (DefaultKdfConfigTest). Argon2id is the default, so this mirrors production.
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)

    private fun newVault() = VaultSession.createVault(engine, kdf, pw(), cheapKdf)

    // Biometric-prompt doubles for the hardware-slot paths.
    private val approve = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Approve)

    // Enroll a hardware slot with an approving prompt; unwraps the Outcome for the happy-path tests.
    private suspend fun VaultSession.enroll(keyStore: KeyStoreBlob, sks: SecureKeyStore): KeyStoreBlob =
        (addHardwareSlot(keyStore, sks, approve) as Outcome.Ok).value

    @Test
    fun create_then_unlock_via_passphrase_recovers_a_working_session() {
        val created = newVault()
        val session = VaultSession.unlock(engine, kdf, created.keyStore, pw(), Slot.PASSPHRASE, ).getOrNull()
        assertTrue(session != null)
        val wrapped = session.generateDek()
        assertTrue(session.useDek(wrapped) { it.size } is Outcome.Ok)
    }

    @Test
    fun create_then_unlock_via_recovery_key_recovers_the_same_master_key() {
        val created = newVault()
        val wrapped = created.session.generateDek()

        val recoveryRaw = RecoveryKey.parse(created.recoveryKey).getOrNull()!!
        val viaRecovery = VaultSession.unlock(engine, kdf, created.keyStore, recoveryRaw, Slot.RECOVERY).getOrNull()!!

        // The DEK wrapped under the original session must unwrap under the recovery-unlocked session:
        // both hold the identical MK.
        assertEquals(DchmFormat.DEK_SIZE, viaRecovery.useDek(wrapped) { it.size }.getOrNull())
    }

    // --- sealMeta / openMeta: item metadata sealed under MK --------------------------------------

    @Test
    fun seal_meta_round_trips_arbitrary_bytes() {
        val session = newVault().session
        for (meta in listOf(ByteArray(0), "name.jpg\u0000image/jpeg".encodeToByteArray(), ByteArray(500) { it.toByte() })) {
            val blob = session.sealMeta(meta)
            assertContentEquals(meta, (session.openMeta(blob) as Outcome.Ok).value)
        }
    }

    @Test
    fun a_dek_wrap_does_not_open_as_meta_and_vice_versa() {
        // Same MK, distinct AAD → cross-opening must fail the GCM tag (IntegrityFailure), so a meta
        // blob can never be mistaken for a wrapped DEK or vice-versa.
        val session = newVault().session
        val metaBlob = session.sealMeta("meta".encodeToByteArray())
        val dekBlob = session.generateDek()
        assertTrue(session.unwrapDek(metaBlob) is Outcome.Err, "a meta blob must not open as a DEK")
        assertTrue(session.openMeta(dekBlob) is Outcome.Err, "a DEK blob must not open as meta")
        assertEquals(VaultError.Crypto.IntegrityFailure, session.openMeta(dekBlob).let { (it as Outcome.Err).error })
    }

    @Test
    fun open_meta_rejects_a_structurally_short_blob() {
        val session = newVault().session
        val short = ByteArray(WRAP_NONCE_SIZE) // nonce only, no ciphertext+tag
        assertEquals(VaultError.Crypto.MalformedContainer, (session.openMeta(short) as Outcome.Err).error)
    }

    @Test
    fun seal_and_open_meta_fail_closed_after_close() {
        val session = newVault().session
        session.close()
        assertFailsWith<IllegalStateException> { session.sealMeta("x".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { session.openMeta(ByteArray(WRAP_NONCE_SIZE + 16)) }
    }

    @Test
    fun use_dek_zeroizes_the_dek_on_the_normal_path() {
        val created = newVault()
        val wrapped = created.session.generateDek()
        var leaked: ByteArray? = null
        created.session.useDek(wrapped) { dek -> leaked = dek }
        assertTrue(leaked!!.all { it == 0.toByte() }, "DEK must be zeroized after useDek returns")
    }

    @Test
    fun use_dek_zeroizes_the_dek_even_when_the_block_throws() {
        val created = newVault()
        val wrapped = created.session.generateDek()
        var leaked: ByteArray? = null
        assertFailsWith<RuntimeException> {
            created.session.useDek(wrapped) { dek ->
                leaked = dek
                throw RuntimeException("boom")
            }
        }
        assertTrue(leaked!!.all { it == 0.toByte() }, "DEK must be zeroized even on the exception path")
    }

    @Test
    fun unwrap_dek_rejects_a_structurally_short_blob() {
        val created = newVault()
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), created.session.unwrapDek(ByteArray(WRAP_NONCE_SIZE)))
    }

    @Test
    fun a_wrong_passphrase_fails_to_unlock() {
        val created = newVault()
        val result = VaultSession.unlock(engine, kdf, created.keyStore, "wrong".encodeToByteArray(), Slot.PASSPHRASE)
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), result)
    }

    @Test
    fun change_passphrase_preserves_the_master_key_and_retires_the_old_one() {
        val created = newVault()
        val wrapped = created.session.generateDek() // wrapped under MK, before the passphrase change

        val newBlob = created.session.changePassphrase(created.keyStore, pw(), "new secret".encodeToByteArray(), cheapKdf).getOrNull()!!

        // New passphrase unlocks and the pre-change DEK still opens → MK unchanged.
        val session2 = VaultSession.unlock(engine, kdf, newBlob, "new secret".encodeToByteArray(), Slot.PASSPHRASE).getOrNull()!!
        assertEquals(DchmFormat.DEK_SIZE, session2.useDek(wrapped) { it.size }.getOrNull())

        // The old passphrase no longer unlocks the new KeyStore.
        assertEquals(
            Outcome.Err(VaultError.Crypto.IntegrityFailure),
            VaultSession.unlock(engine, kdf, newBlob, pw(), Slot.PASSPHRASE),
        )
    }

    @Test
    fun add_recovery_slot_yields_a_key_that_unlocks_the_same_master_key() {
        val created = newVault()
        val wrapped = created.session.generateDek()
        val (blob2, newRecovery) = created.session.addRecoverySlot(created.keyStore)

        val raw = RecoveryKey.parse(newRecovery).getOrNull()!!
        val session2 = VaultSession.unlock(engine, kdf, blob2, raw, Slot.RECOVERY).getOrNull()!!
        assertEquals(DchmFormat.DEK_SIZE, session2.useDek(wrapped) { it.size }.getOrNull())
    }

    @Test
    fun create_vault_zeroizes_the_passphrase_argument() {
        val passphrase = pw()
        VaultSession.createVault(engine, kdf, passphrase, cheapKdf)
        assertTrue(passphrase.all { it == 0.toByte() }, "createVault must zeroize the passphrase it consumes")
    }

    @Test
    fun a_default_created_vault_wraps_the_passphrase_with_argon2id() {
        // The default profile is Argon2id — a new vault's passphrase slot must name it (the cheap
        // profile here is still Argon2id, so this holds without paying the production cost).
        val created = newVault()
        val params = created.keyStore.wraps.first { it.slot == Slot.PASSPHRASE }.kdfParams
        assertTrue(params is KdfParams.Argon2id, "default passphrase wrap must be Argon2id, was ${params.kdfId}")
        created.session.close()
    }

    @Test
    fun a_pbkdf2_vault_still_creates_and_unlocks() {
        // PBKDF2 remains a supported fallback profile — old/opt-in vaults must round-trip.
        val created = VaultSession.createVault(engine, kdf, pw(), PassphraseKdfProfile.Pbkdf2(1000))
        val pwParams = created.keyStore.wraps.first { it.slot == Slot.PASSPHRASE }.kdfParams
        assertTrue(pwParams is KdfParams.Pbkdf2)
        val session = VaultSession.unlock(engine, kdf, created.keyStore, pw(), Slot.PASSPHRASE).getOrNull()
        assertTrue(session != null)
        val wrapped = session.generateDek()
        assertEquals(DchmFormat.DEK_SIZE, session.useDek(wrapped) { it.size }.getOrNull())
    }

    @Test
    fun unlock_consumes_and_zeroizes_the_secret() {
        val created = newVault()
        val secret = pw()
        VaultSession.unlock(engine, kdf, created.keyStore, secret, Slot.PASSPHRASE)
        assertTrue(secret.all { it == 0.toByte() }, "unlock must zeroize the secret it consumes")
    }

    @Test
    fun change_passphrase_consumes_and_zeroizes_both_secrets() {
        val created = newVault()
        val old = pw()
        val fresh = "new secret".encodeToByteArray()
        created.session.changePassphrase(created.keyStore, old, fresh, cheapKdf)
        assertTrue(old.all { it == 0.toByte() }, "old passphrase must be zeroized")
        assertTrue(fresh.all { it == 0.toByte() }, "new passphrase must be zeroized")
    }

    @Test
    fun close_is_idempotent_and_every_operation_is_rejected_afterwards() {
        val created = newVault()
        created.session.close()
        created.session.close() // idempotent — no throw
        assertFailsWith<IllegalStateException> { created.session.generateDek() }
        assertFailsWith<IllegalStateException> { created.session.unwrapDek(ByteArray(48)) }
        assertFailsWith<IllegalStateException> { created.session.useDek(ByteArray(48)) { it } }
    }

    @Test
    fun unlock_reports_a_missing_slot_as_malformed() {
        // A KeyStore with only a PASSPHRASE slot cannot be unlocked via HARDWARE.
        val created = newVault()
        assertFalse(created.keyStore.wraps.any { it.slot == Slot.HARDWARE })
        assertEquals(
            Outcome.Err(VaultError.Crypto.MalformedContainer),
            VaultSession.unlock(engine, kdf, created.keyStore, ByteArray(32), Slot.HARDWARE),
        )
    }

    @Test
    fun add_hardware_slot_then_unlock_with_hardware_recovers_the_same_master_key() = runTest {
        val created = newVault()
        val wrapped = created.session.generateDek() // wrapped under MK, before the hardware slot exists
        val sks = FakeSecureKeyStore()

        val blob2 = created.session.enroll(created.keyStore, sks)
        assertTrue(blob2.hardwareWrap != null)

        val viaHardware = VaultSession.unlockWithHardware(engine, blob2, sks, approve).getOrNull()!!
        assertEquals(DchmFormat.DEK_SIZE, viaHardware.useDek(wrapped) { it.size }.getOrNull())
    }

    @Test
    fun the_hardware_slot_coexists_with_passphrase_and_recovery() = runTest {
        val created = newVault()
        val (withRecovery, recovery) = created.session.addRecoverySlot(created.keyStore)
        val sks = FakeSecureKeyStore()
        val blob = created.session.enroll(withRecovery, sks)
        val wrapped = created.session.generateDek()

        // All three paths open the same MK.
        assertEquals(DchmFormat.DEK_SIZE, VaultSession.unlock(engine, kdf, blob, pw(), Slot.PASSPHRASE).getOrNull()!!.useDek(wrapped) { it.size }.getOrNull())
        assertEquals(DchmFormat.DEK_SIZE, VaultSession.unlock(engine, kdf, blob, RecoveryKey.parse(recovery).getOrNull()!!, Slot.RECOVERY).getOrNull()!!.useDek(wrapped) { it.size }.getOrNull())
        assertEquals(DchmFormat.DEK_SIZE, VaultSession.unlockWithHardware(engine, blob, sks, approve).getOrNull()!!.useDek(wrapped) { it.size }.getOrNull())
    }

    @Test
    fun a_denied_biometric_prompt_fails_hardware_unlock_but_passphrase_still_works() = runTest {
        // §3.3 fallback: a cancelled prompt returns Auth.Cancelled from the hardware path, and the
        // vault still opens by passphrase (the hardware slot is one of several, never the only path).
        val created = newVault()
        val sks = FakeSecureKeyStore()
        val blob = created.session.enroll(created.keyStore, sks)

        val deny = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Deny)
        assertEquals(
            Outcome.Err(VaultError.Auth.Cancelled),
            VaultSession.unlockWithHardware(engine, blob, sks, deny),
        )
        assertTrue(VaultSession.unlock(engine, kdf, blob, pw(), Slot.PASSPHRASE).getOrNull() != null)
    }

    @Test
    fun an_invalidated_key_fails_hardware_unlock_with_integrity_failure_but_passphrase_still_works() = runTest {
        // §3.3: re-enrolled biometrics permanently invalidate the hardware key → IntegrityFailure
        // (distinct from a benign cancel); the forced-passphrase fallback must still unlock.
        val created = newVault()
        val sks = FakeSecureKeyStore()
        val blob = created.session.enroll(created.keyStore, sks)

        val invalidated = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Invalidated)
        assertEquals(
            Outcome.Err(VaultError.Crypto.IntegrityFailure),
            VaultSession.unlockWithHardware(engine, blob, sks, invalidated),
        )
        assertTrue(VaultSession.unlock(engine, kdf, blob, pw(), Slot.PASSPHRASE).getOrNull() != null)
    }

    @Test
    fun a_denied_prompt_fails_enrollment_and_leaves_no_hardware_slot() = runTest {
        val created = newVault()
        val sks = FakeSecureKeyStore()
        val deny = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Deny)
        assertEquals(
            Outcome.Err(VaultError.Auth.Cancelled),
            created.session.addHardwareSlot(created.keyStore, sks, deny),
        )
    }

    @Test
    fun remove_hardware_slot_disables_hardware_unlock_but_leaves_passphrase_working() = runTest {
        val created = newVault()
        val sks = FakeSecureKeyStore()
        val added = created.session.enroll(created.keyStore, sks)

        val removed = created.session.removeHardwareSlot(added, sks)
        assertNull(removed.hardwareWrap)

        // Hardware unlock is gone (no slot); passphrase still works.
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), VaultSession.unlockWithHardware(engine, removed, sks, approve))
        assertTrue(VaultSession.unlock(engine, kdf, removed, pw(), Slot.PASSPHRASE).getOrNull() != null)
    }

    @Test
    fun deleting_the_hardware_key_makes_a_stale_hardware_blob_fail_with_integrity_failure() = runTest {
        val created = newVault()
        val sks = FakeSecureKeyStore()
        val added = created.session.enroll(created.keyStore, sks)

        // Simulate the platform key being invalidated/destroyed out from under a persisted blob.
        sks.deleteHardwareKey()
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), VaultSession.unlockWithHardware(engine, added, sks, approve))
    }

    @Test
    fun re_adding_a_hardware_slot_after_removal_unlocks_again() = runTest {
        val created = newVault()
        val wrapped = created.session.generateDek()
        val sks = FakeSecureKeyStore()

        val removed = created.session.removeHardwareSlot(created.session.enroll(created.keyStore, sks), sks)
        val readded = created.session.enroll(removed, sks)

        val viaHardware = VaultSession.unlockWithHardware(engine, readded, sks, approve).getOrNull()!!
        assertEquals(DchmFormat.DEK_SIZE, viaHardware.useDek(wrapped) { it.size }.getOrNull())
    }

    @Test
    fun unlock_with_hardware_on_a_blob_without_a_hardware_slot_is_malformed() = runTest {
        val created = newVault()
        assertNull(created.keyStore.hardwareWrap)
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), VaultSession.unlockWithHardware(engine, created.keyStore, FakeSecureKeyStore(), approve))
    }

    // --- a lock racing an unwrap ------------------------------------------------------------------

    // An engine that runs [beforeOpen] inside the next aeadOpen, before the real one: where a lock
    // on another thread lands when it comes after the session's closed check.
    private class LockMidOpen(private val real: CryptoEngine) : CryptoEngine by real {
        var beforeOpen: (() -> Unit)? = null

        override fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): Outcome<ByteArray> {
            beforeOpen?.also { beforeOpen = null }?.invoke()
            return real.aeadOpen(key, nonce, aad, ciphertext)
        }
    }

    @Test
    fun a_lock_landing_inside_the_unwrap_is_a_closed_session_not_tampering() {
        // The lock zeroes the master key under the open, so the tag fails. That is the lock's doing,
        // and it must read as the closed session it is: a caller that shows "possible tampering" on
        // IntegrityFailure, or deletes the object, must not see one here.
        for (unwrap in listOf<(VaultSession, ByteArray) -> Unit>(
            { session, wrapped -> session.unwrapDek(wrapped) },
            { session, wrapped -> session.useDek(wrapped) { } },
        )) {
            val racing = LockMidOpen(engine)
            val session = VaultSession.createVault(racing, DefaultKeyDerivation(racing), pw(), cheapKdf).session
            val wrapped = session.generateDek()
            racing.beforeOpen = { session.close() }

            assertFailsWith<IllegalStateException> { unwrap(session, wrapped) }
        }
    }

    @Test
    fun a_lock_landing_inside_a_metadata_open_is_a_closed_session_not_tampering() {
        // The same race on the listing's path: every row's name is opened through openMeta, and a
        // lock mid-open must not draw that row as damaged.
        val racing = LockMidOpen(engine)
        val session = VaultSession.createVault(racing, DefaultKeyDerivation(racing), pw(), cheapKdf).session
        val blob = session.sealMeta("name.jpg".encodeToByteArray())
        racing.beforeOpen = { session.close() }

        assertFailsWith<IllegalStateException> { session.openMeta(blob) }
    }

    // The seal side of the race: an engine that runs [beforeSeal] inside the next aeadSeal.
    private class LockMidSeal(private val real: CryptoEngine) : CryptoEngine by real {
        var beforeSeal: (() -> Unit)? = null

        override fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
            beforeSeal?.also { beforeSeal = null }?.invoke()
            return real.aeadSeal(key, nonce, aad, plaintext)
        }
    }

    @Test
    fun a_lock_landing_inside_a_seal_returns_nothing_sealed_under_the_zeroed_key() {
        // A seal never fails: one the lock reached seals under a zeroed (or half-zeroed) master key
        // and returns a blob nothing can ever open. An item row or album name written with it would
        // read as damaged for good, so the seal must refuse instead of returning it.
        for (seal in listOf<(VaultSession) -> Unit>(
            { session -> session.sealMeta("name.jpg".encodeToByteArray()) },
            { session -> session.generateDek() },
        )) {
            val racing = LockMidSeal(engine)
            val session = VaultSession.createVault(racing, DefaultKeyDerivation(racing), pw(), cheapKdf).session
            racing.beforeSeal = { session.close() }

            assertFailsWith<IllegalStateException> { seal(session) }
        }
    }

    @Test
    fun a_metadata_blob_that_fails_on_a_live_session_is_still_tampering() {
        val session = newVault().session
        val blob = session.sealMeta("name.jpg".encodeToByteArray()).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), session.openMeta(blob))
    }

    @Test
    fun a_wrapped_key_that_fails_on_a_live_session_is_still_tampering() {
        // The other side of the re-check: with no lock, a tag failure stays IntegrityFailure.
        val session = newVault().session
        val wrapped = session.generateDek().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), session.unwrapDek(wrapped))
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), session.useDek(wrapped) { })
    }

    @Test
    fun add_hardware_slot_requires_an_available_keystore() = runTest {
        val created = newVault()
        assertFailsWith<IllegalStateException> {
            created.session.addHardwareSlot(created.keyStore, FakeSecureKeyStore(available = false), approve)
        }
    }
}
