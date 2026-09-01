package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DchmFormat
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.db.Items
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.flatMap
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * Vault-object operations — the one place `core:crypto` (session/codec), `core:storage` (objects),
 * and `core:db` (catalog rows) compose (5c). Everything streams in bounded RAM (O(chunkSize)) and
 * returns [Outcome]; no exception crosses the boundary, and no cipher primitive is imported here —
 * `.dchm` bytes flow through storage opaque, crypto stays behind [DchmCodec] / [VaultSession].
 *
 * [clock] supplies `created_at` (epoch millis); [random] MUST be a CSPRNG in production — it mints
 * the per-object `file_id` and the GCM `nonce_prefix`, whose uniqueness the container's security
 * depends on. The `:app` composition root injects the [VaultSession] engine's secure RNG.
 */
class VaultObjects(
    private val session: VaultSession,
    private val storage: VaultStorage,
    private val catalog: CatalogStore,
    private val codec: DchmCodec,
    private val clock: () -> Long,
    private val random: (Int) -> ByteArray,
) {
    /**
     * Stream-encrypt the plaintext from [openSource] into the vault: a fresh per-object DEK (wrapped
     * under MK), the `.dchm` payload written atomically to the object store, and a catalog row
     * carrying the wrapped DEK + a minimal `enc_meta` ({name, mime}). Returns the new 16-byte
     * `file_id`.
     *
     * [openSource] MUST return a fresh, independent source on each call — the codec reads it twice
     * (size+digest, then chunks). **Fail-closed:** if the row insert fails after the object is
     * written, the object is deleted so no orphan ciphertext is left behind.
     */
    fun importObject(
        kind: Int,
        name: String,
        mime: String,
        chunkSize: Int = DchmFormat.DEFAULT_CHUNK_SIZE,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> {
        val fileId = random(FILE_ID_SIZE)
        val noncePrefix = random(DchmFormat.NONCE_PREFIX_SIZE)
        val createdAt = clock()
        val dekWrapped = session.generateDek()

        // 1. Stream-encrypt the payload into the object store (atomic temp + move). The codec's
        //    precise Outcome is captured inside the write block; on a codec Err we abort the atomic
        //    write (throw the sentinel → storage deletes the temp) and surface that error, not a
        //    generic Io. useDek never sees a throw escape (storage catches it), so its own throw
        //    contract is respected.
        val size = when (val streamed = streamEncrypt(dekWrapped, fileId, noncePrefix, createdAt, name, mime, chunkSize, openSource)) {
            is Outcome.Err -> return streamed
            is Outcome.Ok -> streamed.value
        }

        // 2. Seal the metadata and persist the row. Fail-closed: a row failure removes the object.
        val encMeta = session.sealMeta(encodeMeta(ObjectMeta(name, mime)))
        val row = Items(
            id = fileId,
            album_id = null,
            kind = kind,
            size = size,
            created_at = createdAt,
            enc_meta = encMeta,
            dek_wrapped = dekWrapped,
        )
        return when (val upsert = catalog.upsertItem(row)) {
            is Outcome.Ok -> Outcome.Ok(fileId)
            is Outcome.Err -> {
                storage.deleteObject(fileId) // best-effort; no orphan object without a row
                upsert
            }
        }
    }

    /**
     * Stream-decrypt the object [id] into [sink] in bounded RAM. The wrapped DEK comes from the
     * catalog row; the `nonce_prefix` comes from the container prelude. An unknown id →
     * `VaultError.Io(NoSuchElementException)`; codec failures propagate exactly
     * (`IntegrityFailure` / `UnsupportedFormatVersion` / `MalformedContainer`).
     */
    fun openObject(id: ByteArray, sink: RawSink): Outcome<Unit> {
        val row = when (val got = catalog.getItem(id)) {
            is Outcome.Err -> return got
            is Outcome.Ok -> got.value ?: return absent(id)
        }
        // Triple-nested: useDek(readObject(decryptContainer)); each layer wraps in an Outcome.
        // flatMap { it } collapses one level of nesting, keeping the innermost precise error.
        return session.useDek(row.dek_wrapped) { rawDek ->
            storage.readObject(id) { src -> codec.decryptContainer(rawDek, id, src, sink) }
        }.flatMap { it }.flatMap { it }
    }

    // Runs the encrypt inside a scoped DEK + an atomic object write, returning the measured size.
    private fun streamEncrypt(
        dekWrapped: ByteArray,
        fileId: ByteArray,
        noncePrefix: ByteArray,
        createdAt: Long,
        name: String,
        mime: String,
        chunkSize: Int,
        openSource: () -> RawSource,
    ): Outcome<Long> =
        session.useDek(dekWrapped) { rawDek ->
            var enc: Outcome<Long>? = null
            val write: Outcome<Unit> = storage.writeObject(fileId) { sink ->
                enc = codec.encryptContainer(rawDek, noncePrefix, createdAt, fileId, name, mime, chunkSize, openSource, sink)
                if (enc is Outcome.Err) throw AbortWrite // abort atomic move; storage deletes the temp
            }
            when (val e = enc) {
                // codec threw before returning (e.g. a precondition); prefer storage's real Io error.
                null -> when (write) {
                    is Outcome.Err -> write
                    is Outcome.Ok -> Outcome.Err(VaultError.Unexpected(IllegalStateException("encrypt did not complete")))
                }
                is Outcome.Err -> e // precise codec error (temp already cleaned up)
                is Outcome.Ok -> when (write) {
                    is Outcome.Ok -> e // stored; the measured size
                    is Outcome.Err -> write // disk failure after codec Ok
                }
            }
        }.flatMap { it } // collapse useDek's wrapper; the inner Outcome<Long> carries the result

    private fun absent(id: ByteArray): Outcome.Err =
        Outcome.Err(VaultError.Io(NoSuchElementException("no vault object for id (${id.size} bytes)")))

    private companion object {
        // 128-bit random object id; the catalog primary key and the codec's chunk-AAD file_id.
        const val FILE_ID_SIZE = 16
    }
}

// Internal sentinel: thrown from the write block to abort the atomic object write on a codec Err.
// storage.writeObject catches it (deleting the temp); it never escapes VaultObjects.
private object AbortWrite : RuntimeException()
