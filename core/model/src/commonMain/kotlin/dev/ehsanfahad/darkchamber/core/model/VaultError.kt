package dev.ehsanfahad.darkchamber.core.model

/**
 * Typed error surface for the vault, returned via [Outcome] (docs/engineering/PATTERNS.md §5).
 *
 * Sealed so later cycles add cases (auth, keystore, storage) without breaking exhaustive
 * handling. This cycle (2a — the `.dchm` codec) defines only the cases the container needs.
 */
sealed interface VaultError {
    /** Cryptographic / container-format failures. */
    sealed interface Crypto : VaultError {
        /** GCM tag mismatch — possible tampering or corruption. Never silently retried or partially rendered. */
        data object IntegrityFailure : Crypto

        /** Container is a newer format/cipher than this app supports — surfaces the "update the app" path. */
        data object UnsupportedFormatVersion : Crypto
    }

    /** Underlying I/O failure while reading or writing a container. */
    data class Io(val cause: Throwable) : VaultError
}
