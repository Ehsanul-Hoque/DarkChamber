package dev.ehsanfahad.darkchamber.core.crypto

/**
 * The passphrase-slot KDF choice and its cost parameters **minus the salt** — [VaultSession]
 * randomises a fresh salt for every wrap it writes, so a profile is reusable and salt-free.
 *
 * New vaults default to [Argon2idDefault] (the memory-hard production cost). PBKDF2 stays selectable
 * as the documented fallback (FORMAT.md §3). Tests construct cheap profiles via the public
 * constructors so a fast wrap doesn't pay the 64 MiB Argon2id cost; the production default is
 * asserted once, separately (`DefaultKdfConfigTest`).
 */
sealed interface PassphraseKdfProfile {
    /** The stored [KdfParams] for this profile with the caller-supplied per-wrap [salt]. */
    fun paramsWithSalt(salt: ByteArray): KdfParams

    class Argon2id(
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
    ) : PassphraseKdfProfile {
        override fun paramsWithSalt(salt: ByteArray): KdfParams =
            KdfParams.Argon2id(salt, memoryKib, iterations, parallelism)
    }

    class Pbkdf2(val iterations: Int) : PassphraseKdfProfile {
        override fun paramsWithSalt(salt: ByteArray): KdfParams =
            KdfParams.Pbkdf2(salt, iterations)
    }

    companion object {
        /** Production default: Argon2id at m=64 MiB, t=3, p=2 (FORMAT.md §3). */
        val Argon2idDefault: PassphraseKdfProfile =
            Argon2id(ARGON2ID_DEFAULT_MEMORY_KIB, ARGON2ID_DEFAULT_ITERATIONS, ARGON2ID_DEFAULT_PARALLELISM)
    }
}
