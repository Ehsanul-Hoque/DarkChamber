package dev.ehsanfahad.darkchamber.app.player

import dev.ehsanfahad.darkchamber.core.model.VaultError
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which failed reads Media3 may try again: never one the vault refused on cryptographic grounds. */
class VaultLoadErrorPolicyTest {
    @Test
    fun a_read_refused_by_the_integrity_check_is_final() {
        assertTrue(isVaultVerdict(VaultReadException(VaultError.Crypto.IntegrityFailure)))
        assertTrue(isVaultVerdict(VaultReadException(VaultError.Crypto.MalformedContainer)))
        assertTrue(isVaultVerdict(VaultReadException(VaultError.Crypto.UnsupportedFormatVersion)))
    }

    @Test
    fun it_is_final_however_deep_the_player_wrapped_it() {
        val wrapped =
            IOException("loader", RuntimeException("source", VaultReadException(VaultError.Crypto.IntegrityFailure)))
        assertTrue(isVaultVerdict(wrapped))
    }

    @Test
    fun any_other_failed_read_keeps_the_players_own_retries() {
        assertFalse(isVaultVerdict(VaultReadException(VaultError.Io(IOException("busy")))), "an I/O hiccup may pass")
        assertFalse(isVaultVerdict(IOException("something else")))
    }
}
