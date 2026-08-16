package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultSessionTest {
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)

    // A fresh passphrase array each call — createVault/unlock consume (zeroize) the ones they take.
    private fun pw() = "correct horse battery staple".encodeToByteArray()

    // Fast KDF for tests; the 600k production default is asserted separately (TASK-009).
    private fun newVault() = VaultSession.createVault(engine, kdf, pw(), pbkdf2Iterations = 1000)

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

        val newBlob = created.session.changePassphrase(created.keyStore, pw(), "new secret".encodeToByteArray(), pbkdf2Iterations = 1000).getOrNull()!!

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
        VaultSession.createVault(engine, kdf, passphrase, pbkdf2Iterations = 1000)
        assertTrue(passphrase.all { it == 0.toByte() }, "createVault must zeroize the passphrase it consumes")
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
}
