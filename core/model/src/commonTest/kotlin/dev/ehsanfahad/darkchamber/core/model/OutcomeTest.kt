package dev.ehsanfahad.darkchamber.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutcomeTest {
    @Test
    fun ok_carries_its_value_and_fold_routes_to_onOk() {
        val outcome: Outcome<Int> = Outcome.Ok(42)
        val folded = outcome.fold(onOk = { "ok:$it" }, onErr = { "err:$it" })
        assertEquals("ok:42", folded)
    }

    @Test
    fun err_carries_its_error_and_fold_routes_to_onErr() {
        val outcome: Outcome<Int> = Outcome.Err(VaultError.Crypto.IntegrityFailure)
        val folded = outcome.fold(onOk = { "ok" }, onErr = { it })
        assertEquals(VaultError.Crypto.IntegrityFailure, folded)
    }

    @Test
    fun map_transforms_the_value_of_an_ok() {
        val mapped = Outcome.Ok(21).map { it * 2 }
        assertEquals(Outcome.Ok(42), mapped)
    }

    @Test
    fun map_passes_an_err_through_untouched() {
        val err: Outcome<Int> = Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion)
        val mapped = err.map { it * 2 }
        assertEquals(Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion), mapped)
    }

    @Test
    fun getOrNull_returns_the_value_for_ok_and_null_for_err() {
        assertEquals(7, Outcome.Ok(7).getOrNull())
        assertNull((Outcome.Err(VaultError.Crypto.IntegrityFailure) as Outcome<Int>).getOrNull())
    }
}
