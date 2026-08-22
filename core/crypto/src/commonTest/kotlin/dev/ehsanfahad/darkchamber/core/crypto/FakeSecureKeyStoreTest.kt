package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the [SecureKeyStore] contract via [FakeSecureKeyStore] so the HARDWARE-slot integration
 * (TASK-004) can rely on it. The real platform actuals must honour the same contract; they are
 * verified by the on-device checklist, not here.
 */
class FakeSecureKeyStoreTest {
    private val sks = FakeSecureKeyStore()
    private val mk = ByteArray(MK_SIZE) { it.toByte() }

    @Test
    fun wrap_then_unwrap_recovers_the_master_key() {
        val blob = sks.wrap(mk)
        assertContentEquals(mk, sks.unwrap(blob).getOrNull())
    }

    @Test
    fun each_wrap_uses_a_fresh_nonce_so_blobs_differ() {
        assertFalse(sks.wrap(mk).contentEquals(sks.wrap(mk)))
    }

    @Test
    fun a_tampered_blob_fails_with_integrity_failure() {
        val blob = sks.wrap(mk).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), sks.unwrap(blob))
    }

    @Test
    fun delete_makes_a_subsequent_unwrap_fail_with_integrity_failure() {
        val blob = sks.wrap(mk)
        sks.deleteHardwareKey()
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), sks.unwrap(blob))
    }

    @Test
    fun delete_is_idempotent() {
        sks.deleteHardwareKey()
        sks.deleteHardwareKey() // second delete must not throw
        // a fresh wrap still round-trips (delete left the store in a usable state)
        assertContentEquals(mk, sks.unwrap(sks.wrap(mk)).getOrNull())
    }

    @Test
    fun re_wrap_after_delete_uses_a_new_key_and_still_round_trips() {
        val old = sks.wrap(mk)
        sks.deleteHardwareKey()
        val fresh = sks.wrap(mk)
        // the old blob no longer opens under the regenerated key...
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), sks.unwrap(old))
        // ...but the fresh one does.
        assertContentEquals(mk, sks.unwrap(fresh).getOrNull())
    }

    @Test
    fun is_available_reflects_the_constructor_flag() {
        assertTrue(FakeSecureKeyStore(available = true).isAvailable())
        assertFalse(FakeSecureKeyStore(available = false).isAvailable())
    }
}
