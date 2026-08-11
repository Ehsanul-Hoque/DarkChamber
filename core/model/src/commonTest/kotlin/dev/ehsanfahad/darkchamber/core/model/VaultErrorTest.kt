package dev.ehsanfahad.darkchamber.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
}
