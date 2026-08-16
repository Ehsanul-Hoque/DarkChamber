package dev.ehsanfahad.darkchamber.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the *production* KDF cost, separately from the fast vector gate (which runs at 1000 iters).
 * A real vault created at the default config must stretch the passphrase at the FORMAT.md §3 target,
 * so a careless change to the default surfaces here. One genuine 600k derive — not looped.
 */
class DefaultKdfConfigTest {
    @Test
    fun a_default_created_vault_stretches_the_passphrase_at_the_production_cost() {
        assertEquals(600_000, PBKDF2_DEFAULT_ITERS)

        val engine = CryptographyKotlinEngine()
        val created = VaultSession.createVault(engine, DefaultKeyDerivation(engine), "a passphrase".encodeToByteArray())
        val passphraseParams = created.keyStore.wraps.first { it.slot == Slot.PASSPHRASE }.kdfParams
        assertEquals(PBKDF2_DEFAULT_ITERS, (passphraseParams as KdfParams.Pbkdf2).iterations)
        created.session.close()
    }
}
