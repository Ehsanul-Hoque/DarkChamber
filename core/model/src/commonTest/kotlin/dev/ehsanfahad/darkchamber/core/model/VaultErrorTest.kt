package dev.ehsanfahad.darkchamber.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VaultErrorTest {
    @Test
    fun crypto_variants_are_distinct() {
        assertNotEquals<VaultError>(
            VaultError.Crypto.IntegrityFailure,
            VaultError.Crypto.UnsupportedFormatVersion,
        )
    }

    @Test
    fun io_carries_its_cause() {
        val cause = IllegalStateException("boom")
        assertEquals(cause, (VaultError.Io(cause)).cause)
    }

    @Test
    fun db_is_a_vault_error_and_carries_its_cause() {
        val cause = IllegalStateException("catalog closed")
        val error: VaultError = VaultError.Db(cause)
        assertTrue(error is VaultError.Db)
        assertEquals(cause, error.cause)
    }

    @Test
    fun db_is_distinct_from_io_for_the_same_cause() {
        val cause = IllegalStateException("boom")
        assertNotEquals<VaultError>(VaultError.Db(cause), VaultError.Io(cause))
    }
}
