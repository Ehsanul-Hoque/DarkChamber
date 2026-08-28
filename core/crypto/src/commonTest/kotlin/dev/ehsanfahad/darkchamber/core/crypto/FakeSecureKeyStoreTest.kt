package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the [SecureKeyStore] contract via [FakeSecureKeyStore] so the HARDWARE-slot integration
 * (TASK-004) can rely on it. The real platform actuals must honour the same contract; they are
 * verified by the on-device checklist, not here.
 *
 * Every wrap/unwrap here approves the biometric prompt; the deny/invalidated paths live in
 * [VaultSessionTest] where they exercise the unlock flow end-to-end.
 */
class FakeSecureKeyStoreTest {
    private val sks = FakeSecureKeyStore()
    private val approve = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Approve)
    private val mk = ByteArray(MK_SIZE) { it.toByte() }

    private suspend fun wrap(): ByteArray =
        (sks.wrap(mk, approve) as Outcome.Ok).value

    @Test
    fun wrap_then_unwrap_recovers_the_master_key() = runTest {
        val blob = wrap()
        assertContentEquals(mk, sks.unwrap(blob, approve).getOrNull())
    }

    @Test
    fun each_wrap_uses_a_fresh_nonce_so_blobs_differ() = runTest {
        assertFalse(wrap().contentEquals(wrap()))
    }

    @Test
    fun a_tampered_blob_fails_with_integrity_failure() = runTest {
        val blob = wrap().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), sks.unwrap(blob, approve))
    }

    @Test
    fun delete_makes_a_subsequent_unwrap_fail_with_integrity_failure() = runTest {
        val blob = wrap()
        sks.deleteHardwareKey()
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), sks.unwrap(blob, approve))
    }

    @Test
    fun delete_is_idempotent() = runTest {
        sks.deleteHardwareKey()
        sks.deleteHardwareKey() // second delete must not throw
        // a fresh wrap still round-trips (delete left the store in a usable state)
        assertContentEquals(mk, sks.unwrap(wrap(), approve).getOrNull())
    }

    @Test
    fun re_wrap_after_delete_uses_a_new_key_and_still_round_trips() = runTest {
        val old = wrap()
        sks.deleteHardwareKey()
        val fresh = wrap()
        // the old blob no longer opens under the regenerated key...
        assertEquals(Outcome.Err(VaultError.Crypto.IntegrityFailure), sks.unwrap(old, approve))
        // ...but the fresh one does.
        assertContentEquals(mk, sks.unwrap(fresh, approve).getOrNull())
    }

    @Test
    fun a_cancelled_prompt_propagates_auth_cancelled() = runTest {
        val deny = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Deny)
        assertEquals(Outcome.Err(VaultError.Auth.Cancelled), sks.wrap(mk, deny))
        val blob = wrap()
        assertEquals(Outcome.Err(VaultError.Auth.Cancelled), sks.unwrap(blob, deny))
    }

    @Test
    fun is_available_reflects_the_constructor_flag() {
        assertTrue(FakeSecureKeyStore(available = true).isAvailable())
        assertFalse(FakeSecureKeyStore(available = false).isAvailable())
    }
}
