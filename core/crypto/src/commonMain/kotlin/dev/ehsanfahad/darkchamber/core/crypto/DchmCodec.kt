package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.EOFException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * Reads and writes the chunked, authenticated `.dchm` container (FORMAT.md §2), delegating every
 * cipher/hash to a [CryptoEngine]. The codec never buffers the whole object: encode streams the
 * plaintext twice (pass 1 = size + digest, pass 2 = chunks) and decode streams chunk-by-chunk.
 *
 * `fileId` is not stored in the container — it is bound as chunk AAD, so opening a container requires
 * the same `fileId` the catalog assigned it (it is supplied to [decryptContainer] by the caller).
 */
class DchmCodec(private val engine: CryptoEngine) {

    /**
     * Encode plaintext from [openSource] into a `.dchm` container written to [sink]. On success,
     * returns the measured plaintext size in bytes (the value the header records) so callers can fill
     * a catalog `size` column without re-reading the source.
     * [openSource] MUST return a fresh, independent source on each call — the codec reads it twice.
     */
    fun encryptContainer(
        dek: ByteArray,
        noncePrefix: ByteArray,
        createdAt: Long,
        fileId: ByteArray,
        originalName: String,
        mime: String,
        chunkSize: Int,
        openSource: () -> RawSource,
        sink: RawSink,
    ): Outcome<Long> {
        // Caller-contract preconditions: wrong-sized key material or a non-positive chunk size is a
        // programming error (fail loudly), not a runtime condition to swallow.
        require(dek.size == DchmFormat.DEK_SIZE) { "DEK must be ${DchmFormat.DEK_SIZE} bytes, was ${dek.size}" }
        require(noncePrefix.size == DchmFormat.NONCE_PREFIX_SIZE) {
            "nonce_prefix must be ${DchmFormat.NONCE_PREFIX_SIZE} bytes, was ${noncePrefix.size}"
        }
        require(chunkSize > 0) { "chunkSize must be positive, was $chunkSize" }
        return try {
            // Pass 1 — plaintext size + SHA-256, in constant memory.
            val hasher = engine.newSha256()
            var size = 0L
            openSource().buffered().use { source ->
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val n = source.readAtMostTo(buffer, 0, buffer.size)
                    if (n == -1) break
                    hasher.update(buffer, 0, n)
                    size += n
                }
            }

            // Enforce D8 / FORMAT.md §2.3 nonce disjointness: chunk counters must stay below the
            // reserved header counter 0xFFFFFFFF, or a chunk nonce would collide with the header's.
            if (chunkCountFor(size, chunkSize) > DchmFormat.HEADER_NONCE_COUNTER.toLong()) {
                return Outcome.Err(VaultError.Io(IllegalStateException("object too large: chunk count exceeds the nonce space")))
            }

            val header = DchmHeader(originalName, mime, size, createdAt, hasher.digest(), chunkSize)
            val headerCbor = DchmHeaderCodec.encode(header)
            val headerLen = headerCbor.size + DchmFormat.GCM_TAG_SIZE
            if (headerLen > DchmFormat.MAX_HEADER_LEN) {
                return Outcome.Err(VaultError.Io(IllegalStateException("header too large ($headerLen bytes); shorten original_name")))
            }

            // header_len is deterministic (CBOR + GCM tag), so the prelude — which is the header's AAD —
            // can be built before sealing the header.
            val prelude = Prelude(DchmFormat.VERSION, DchmFormat.CIPHER_AES_256_GCM, headerLen, noncePrefix)
            val preludeBytes = prelude.toByteArray()
            val sealedHeader = engine.aeadSeal(dek, headerNonce(noncePrefix), preludeBytes, headerCbor)

            val out = sink.buffered()
            out.write(preludeBytes)
            out.write(sealedHeader)

            // Pass 2 — stream chunks. Empty plaintext = exactly one empty final chunk (FORMAT.md §2.3).
            openSource().buffered().use { source ->
                if (size == 0L) {
                    out.write(sealChunk(dek, noncePrefix, fileId, index = 0, isLast = true, plaintext = ByteArray(0)))
                } else {
                    var index = 0L
                    var remaining = size
                    while (remaining > 0) {
                        val toRead = minOf(chunkSize.toLong(), remaining).toInt()
                        val plaintext = source.readByteArray(toRead)
                        remaining -= toRead
                        out.write(sealChunk(dek, noncePrefix, fileId, index, isLast = remaining == 0L, plaintext))
                        index++
                    }
                }
            }
            out.flush()
            Outcome.Ok(size)
        } catch (e: Exception) {
            Outcome.Err(VaultError.Io(e))
        }
    }

    /**
     * Decode a `.dchm` container from [source] into [sink]. Verifies every chunk's tag before
     * releasing its plaintext, verifies `is_last` on the final chunk (via AAD), rejects trailing
     * bytes (splice), and cross-checks the whole-plaintext SHA-256. Failures map to the precise
     * [VaultError]: a newer format → `UnsupportedFormatVersion`; tampering/corruption/truncation →
     * `IntegrityFailure`; non-container bytes → `MalformedContainer`.
     */
    fun decryptContainer(dek: ByteArray, fileId: ByteArray, source: RawSource, sink: RawSink): Outcome<Unit> {
        val input = source.buffered()
        val preludeBytes = try {
            input.readByteArray(DchmFormat.PRELUDE_SIZE)
        } catch (e: EOFException) {
            return Outcome.Err(VaultError.Crypto.MalformedContainer)
        }
        val prelude = when (val parsed = Prelude.read(preludeBytes)) {
            is Outcome.Err -> return parsed
            is Outcome.Ok -> parsed.value
        }
        return try {
            val sealedHeader = input.readByteArray(prelude.headerLen)
            val headerCbor = when (
                val opened = engine.aeadOpen(dek, headerNonce(prelude.noncePrefix), preludeBytes, sealedHeader)
            ) {
                is Outcome.Err -> return opened
                is Outcome.Ok -> opened.value
            }
            val header = DchmHeaderCodec.decode(headerCbor)

            // Guard against invalid (though authenticated) header fields before driving the chunk loop.
            if (header.chunkSize <= 0 || header.plaintextSize < 0) {
                return Outcome.Err(VaultError.Crypto.MalformedContainer)
            }
            if (chunkCountFor(header.plaintextSize, header.chunkSize) > DchmFormat.HEADER_NONCE_COUNTER.toLong()) {
                return Outcome.Err(VaultError.Crypto.MalformedContainer)
            }

            val out = sink.buffered()
            val hasher = engine.newSha256()
            val size = header.plaintextSize
            if (size == 0L) {
                val ciphertext = input.readByteArray(DchmFormat.GCM_TAG_SIZE)
                val plaintext = when (val opened = openChunk(dek, prelude.noncePrefix, fileId, 0, true, ciphertext)) {
                    is Outcome.Err -> return opened
                    is Outcome.Ok -> opened.value
                }
                hasher.update(plaintext)
                out.write(plaintext)
            } else {
                var index = 0L
                var remaining = size
                while (remaining > 0) {
                    val plainLen = minOf(header.chunkSize.toLong(), remaining).toInt()
                    val ciphertext = input.readByteArray(plainLen + DchmFormat.GCM_TAG_SIZE)
                    remaining -= plainLen
                    val plaintext = when (
                        val opened = openChunk(dek, prelude.noncePrefix, fileId, index, isLast = remaining == 0L, ciphertext)
                    ) {
                        is Outcome.Err -> return opened
                        is Outcome.Ok -> opened.value
                    }
                    hasher.update(plaintext)
                    out.write(plaintext)
                    index++
                }
            }
            // Trailing bytes after the last chunk = a splice/append; reject.
            if (!input.exhausted()) return Outcome.Err(VaultError.Crypto.IntegrityFailure)
            out.flush()
            // Whole-plaintext digest cross-check (each chunk is already individually authenticated).
            if (!hasher.digest().contentEquals(header.sha256Plaintext)) {
                return Outcome.Err(VaultError.Crypto.IntegrityFailure)
            }
            Outcome.Ok(Unit)
        } catch (e: EOFException) {
            // A chunk shorter than its declared length = truncation.
            Outcome.Err(VaultError.Crypto.IntegrityFailure)
        } catch (e: Exception) {
            Outcome.Err(VaultError.Io(e))
        }
    }

    /**
     * Decrypt a single, already-sliced chunk — the random-access / seek primitive (FORMAT.md §1).
     * The caller (e.g. a Media3 `DataSource`) reads the prelude+header once, then seeks to byte
     * `PRELUDE_SIZE + header_len + chunkIndex·(chunkSize + GCM_TAG_SIZE)`, reads that chunk's bytes,
     * and passes them here with the `noncePrefix` from the prelude and the `isLast` it computed from
     * `plaintext_size`. Verifies the tag before returning; a mismatch → `IntegrityFailure`.
     */
    fun decryptChunk(
        dek: ByteArray,
        noncePrefix: ByteArray,
        fileId: ByteArray,
        chunkIndex: Long,
        isLast: Boolean,
        ciphertext: ByteArray,
    ): Outcome<ByteArray> = openChunk(dek, noncePrefix, fileId, chunkIndex, isLast, ciphertext)

    private fun sealChunk(
        dek: ByteArray,
        noncePrefix: ByteArray,
        fileId: ByteArray,
        index: Long,
        isLast: Boolean,
        plaintext: ByteArray,
    ): ByteArray = engine.aeadSeal(dek, chunkNonce(noncePrefix, index), chunkAad(fileId, index, isLast), plaintext)

    private fun openChunk(
        dek: ByteArray,
        noncePrefix: ByteArray,
        fileId: ByteArray,
        index: Long,
        isLast: Boolean,
        ciphertext: ByteArray,
    ): Outcome<ByteArray> =
        engine.aeadOpen(dek, chunkNonce(noncePrefix, index), chunkAad(fileId, index, isLast), ciphertext)
}

/**
 * Number of chunks for a plaintext of [size] bytes at [chunkSize]: `ceil(size / chunkSize)`, but an
 * empty object is exactly one (empty final) chunk (FORMAT.md §2.3). [chunkSize] must be positive.
 */
internal fun chunkCountFor(size: Long, chunkSize: Int): Long =
    if (size == 0L) 1L else (size + chunkSize - 1) / chunkSize

/** GCM nonce for chunk [counter]: `nonce_prefix ‖ u32-LE counter` (FORMAT.md §2.3). */
internal fun chunkNonce(noncePrefix: ByteArray, counter: Long): ByteArray = noncePrefix + u32Le(counter)

/** GCM nonce for the header: the reserved counter `0xFFFFFFFF`, disjoint from every chunk. */
internal fun headerNonce(noncePrefix: ByteArray): ByteArray =
    chunkNonce(noncePrefix, DchmFormat.HEADER_NONCE_COUNTER.toLong())

/** Chunk AAD: `file_id ‖ u32-LE index ‖ u8 is_last` (FORMAT.md §2.3). */
internal fun chunkAad(fileId: ByteArray, index: Long, isLast: Boolean): ByteArray =
    fileId + u32Le(index) + byteArrayOf(if (isLast) 1 else 0)

/** Little-endian u32 encoding of the low 32 bits of [value]. */
internal fun u32Le(value: Long): ByteArray = ByteArray(4) { i -> (value ushr (8 * i)).toByte() }
