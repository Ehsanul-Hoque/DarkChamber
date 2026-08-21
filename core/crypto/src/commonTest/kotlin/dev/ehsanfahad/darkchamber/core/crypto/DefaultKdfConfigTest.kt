package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the *production* passphrase KDF, separately from the fast tests (which use a cheap profile).
 * A real vault created at the default config must stretch the passphrase with Argon2id at the
 * FORMAT.md §3 cost (m=64 MiB, t=3, p=2), so a careless change to the default surfaces here. One
 * genuine Argon2id derive at production cost — not looped.
 */
class DefaultKdfConfigTest {
    @Test
    fun a_default_created_vault_stretches_the_passphrase_with_production_argon2id() {
        val engine = CryptographyKotlinEngine()
        val kdf = DefaultKeyDerivation(engine)
        val created = VaultSession.createVault(engine, kdf, "a passphrase".encodeToByteArray())

        val params = created.keyStore.wraps.first { it.slot == Slot.PASSPHRASE }.kdfParams
        assertTrue(params is KdfParams.Argon2id, "default passphrase KDF must be Argon2id, was ${params.kdfId}")
        assertEquals(ARGON2ID_DEFAULT_MEMORY_KIB, params.memoryKib)
        assertEquals(ARGON2ID_DEFAULT_ITERATIONS, params.iterations)
        assertEquals(ARGON2ID_DEFAULT_PARALLELISM, params.parallelism)

        // The production-cost slot really unlocks (exercises one full 64 MiB derive end-to-end).
        val session = VaultSession.unlock(engine, kdf, created.keyStore, "a passphrase".encodeToByteArray(), Slot.PASSPHRASE).getOrNull()
        assertTrue(session != null)
        session.close()
        created.session.close()
    }
}
