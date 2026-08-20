package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome

/**
 * The single cryptographic seam for DarkChamber (hard rule #2). Every cipher, hash, and
 * secure-random call in the app goes through this interface; the implementation is the ONLY
 * code permitted to import a cryptography primitive library, so the primitive can be swapped
 * without touching the `.dchm` codec, key hierarchy, or any feature.
 *
 * Operations are blocking (the container codec streams over blocking `kotlinx-io` sources).
 * The engine holds no per-object state — keys and nonces are passed in on every call.
 */
interface CryptoEngine {
    /**
     * AES-256-GCM seal. [nonce] is the full 12-byte GCM nonce (caller-controlled, per FORMAT.md),
     * [aad] is bound but not encrypted. Returns ciphertext with the 16-byte GCM tag appended.
     */
    fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /**
     * AES-256-GCM open. Verifies the tag against [aad] and [nonce] before returning plaintext.
     * A tag mismatch (tampering or corruption) yields [Outcome.Err] of
     * [dev.ehsanfahad.darkchamber.core.model.VaultError.Crypto.IntegrityFailure] — never a throw
     * across the boundary, never a partial result.
     */
    fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): Outcome<ByteArray>

    /** SHA-256 of [bytes] (used for the header's plaintext digest). */
    fun sha256(bytes: ByteArray): ByteArray

    /**
     * A fresh incremental SHA-256. Lets the container codec hash a multi-GB plaintext in constant
     * memory (streaming both encode passes) instead of buffering the whole object.
     */
    fun newSha256(): IncrementalHash

    /** [n] cryptographically-secure random bytes (DEKs, nonce prefixes). */
    fun randomBytes(n: Int): ByteArray

    /**
     * PBKDF2-HMAC-SHA256 password stretching → [dkLenBytes] of derived key. [iterations] is the
     * FORMAT.md §3 cost parameter (≥ 600 000 in production). Used only by the key-hierarchy layer to
     * turn a low-entropy passphrase into an MK-wrap key — never for content. The KDF's id and
     * parameters are stored beside each wrap so the cost can be raised over time.
     */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLenBytes: Int): ByteArray

    /**
     * HKDF-SHA256 extract-and-expand → [lenBytes] of derived key. For deriving an MK-wrap key from an
     * already-full-entropy secret (the recovery key), with [info] as the domain-separation label.
     * This is *not* a password stretcher — it does no work-factor hardening and must not be used on
     * low-entropy input.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, lenBytes: Int): ByteArray

    /**
     * Argon2id password stretching → [outLenBytes] of derived key. The memory-hard default for the
     * passphrase MK-wrap slot (FORMAT.md §3): [memoryKib] KiB of memory, [iterations] passes,
     * [parallelism] lanes. The Argon2 version is pinned to 0x13 (RFC 9106 / Argon2 1.3) on every
     * platform; it is not a caller parameter. Backed by a per-platform actual (BouncyCastle on the
     * JVM/Android host, the vendored phc-winner-argon2 cinterop on iOS) — the Argon2 primitive is the
     * only crypto not provided by cryptography-kotlin, so it lives behind this same seam (hard rule #2).
     */
    fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
        outLenBytes: Int,
    ): ByteArray
}

/** A one-shot, updatable hash: feed bytes with [update], read the result once with [digest]. */
interface IncrementalHash {
    fun update(data: ByteArray, startIndex: Int = 0, endIndex: Int = data.size)

    /** Finalize and return the digest. The instance must not be reused afterwards. */
    fun digest(): ByteArray
}
