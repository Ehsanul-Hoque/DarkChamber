package dev.ehsanfahad.darkchamber.core.crypto

/**
 * The one crypto primitive cryptography-kotlin does not provide, bound per platform behind the
 * engine (hard rule #2 — Argon2 imports live only in the actuals, never leak into the codec or
 * feature code). Inputs are already validated by [CryptographyKotlinEngine.argon2id] before this
 * is called; the Argon2 version is fixed at 0x13 (RFC 9106 / Argon2 1.3) in both actuals.
 *
 * - androidMain → BouncyCastle `Argon2BytesGenerator` (pure JVM, so KATs run in `testAndroidHostTest`).
 * - iosMain → vendored phc-winner-argon2 `argon2id_hash_raw` via cinterop.
 */
internal expect fun platformArgon2id(
    password: ByteArray,
    salt: ByteArray,
    memoryKib: Int,
    iterations: Int,
    parallelism: Int,
    outLenBytes: Int,
): ByteArray
