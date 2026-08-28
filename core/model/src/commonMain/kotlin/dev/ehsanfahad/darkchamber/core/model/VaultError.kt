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

        /** Bytes are not a well-formed `.dchm` container (bad magic, truncated prelude) — not "update the app". */
        data object MalformedContainer : Crypto
    }

    /**
     * Biometric / device-auth failure at the `SecureKeyStore`/`BiometricAuthenticator` boundary
     * (cycle 5b). Distinct from [Crypto.IntegrityFailure]: a cancelled prompt or missing enrollment
     * is a benign auth outcome (retry / fall back to passphrase), NOT possible tampering. A
     * permanently invalidated key (biometrics re-enrolled) surfaces as [Crypto.IntegrityFailure]
     * instead, since the wrap can no longer be opened.
     */
    sealed interface Auth : VaultError {
        /** User dismissed/cancelled the prompt, or auth failed without key invalidation. */
        data object Cancelled : Auth

        /** No usable biometric — none enrolled, hardware absent, or temporary lockout. */
        data object Unavailable : Auth
    }

    /** Underlying I/O failure while reading or writing a container. */
    data class Io(val cause: Throwable) : VaultError

    /**
     * Catalog (SQLDelight) failure — schema create/migrate, query, or transaction.
     *
     * Callers fail closed: a catalog that cannot open or migrate cleanly is treated as
     * unusable, never a half-open state that proceeds. The [cause] is carried for the logging
     * boundary (`core:logging`) to redact — this type is never logged directly (hard rule #4:
     * no SQL text, blob, id, or path in logs).
     */
    data class Db(val cause: Throwable) : VaultError

    /**
     * A throwable that escaped a lower layer which should have returned an [Outcome] — the
     * fail-closed catch-all at a boundary (e.g. `VaultBootstrap`). It should never occur on a
     * correct path, but is mapped to a typed error so no exception crosses into the platform glue
     * (docs/engineering/PATTERNS.md §5). The [cause] is carried only for the logging boundary to
     * redact — never logged directly (hard rule #4).
     */
    data class Unexpected(val cause: Throwable) : VaultError
}
