package dev.ehsanfahad.darkchamber.core.crypto

/**
 * Constants of the `.dchm` container format (normative spec: docs/FORMAT.md).
 *
 * Single source of truth for magic/version/cipher ids and sizes, so the codec and the
 * format-vector meta-completeness test read the same values.
 */
object DchmFormat {
    /** ASCII "DCHM". */
    val MAGIC: ByteArray = byteArrayOf(0x44, 0x43, 0x48, 0x4D)

    /** Current format version. */
    const val VERSION: Int = 1

    /** cipher_id 0x01 — AES-256-GCM (the only cipher in v1; 0x02 ChaCha20-Poly1305 is reserved). */
    const val CIPHER_AES_256_GCM: Int = 1

    /** Plaintext prelude: magic(4) ‖ version(1) ‖ cipher_id(1) ‖ header_len(2) ‖ nonce_prefix(8). */
    const val PRELUDE_SIZE: Int = 16

    /** Random per-object GCM nonce prefix, held in the plaintext prelude. */
    const val NONCE_PREFIX_SIZE: Int = 8

    /** GCM authentication tag length appended to every sealed block. */
    const val GCM_TAG_SIZE: Int = 16

    /** Default chunk size — 1 MiB. */
    const val DEFAULT_CHUNK_SIZE: Int = 1 shl 20

    /**
     * Reserved chunk counter for the header nonce (`nonce_prefix ‖ 0xFFFFFFFF`). Chunk indices run
     * `0 … N-1` with `N ≤ 2^32-1`, so header and chunk nonces are disjoint under the shared DEK.
     */
    const val HEADER_NONCE_COUNTER: UInt = 0xFFFFFFFFu

    /**
     * Every supported `(version, cipher_id)` pair. The meta-completeness test enumerates this to
     * assert each has a committed vector set; `Prelude.read` rejects anything absent as unsupported.
     */
    val SUPPORTED: Set<Pair<Int, Int>> = setOf(VERSION to CIPHER_AES_256_GCM)
}
