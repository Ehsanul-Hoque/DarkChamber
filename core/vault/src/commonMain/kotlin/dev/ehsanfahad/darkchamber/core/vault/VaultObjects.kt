package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DchmFormat
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.db.Items
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.model.flatMap
import dev.ehsanfahad.darkchamber.core.model.map
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * Vault-object operations — the one place `core:crypto` (session/codec), `core:storage` (objects),
 * and `core:db` (catalog rows) compose. Everything streams in bounded RAM (O(chunkSize)) and
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
     * The codec reads the payload twice (size+digest, then chunks), so [openSource] should return a
     * fresh, independent source on each call. When it cannot — a content-provider or
     * security-scoped stream that opens exactly once — the payload is spilled into app-private
     * `tmp/` and imported from there (see the re-openability probe below). **Fail-closed:** if the
     * row insert fails after the object is written, the object is deleted so no orphan ciphertext is
     * left behind.
     */
    fun importObject(
        kind: Int,
        name: String,
        mime: String,
        chunkSize: Int = DchmFormat.DEFAULT_CHUNK_SIZE,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> {
        // Probe re-openability BEFORE reading anything. The check cannot be done after a failure:
        // by then the only stream a one-shot source will ever give has already been consumed by the
        // codec's first pass, leaving nothing to spill. So open one source and hold it, then try for
        // a second — if that fails, the held source is the only one there is.
        //
        // Cost on the happy path is two extra open/close calls with no bytes read; the payoff is
        // that a re-openable source streams straight into the container and its plaintext never
        // touches disk at all (hard rule #1). A provider that refuses two *concurrent* opens is
        // spilled unnecessarily — slower, still correct.
        val held = try {
            openSource()
        } catch (e: Exception) {
            return Outcome.Err(VaultError.Io(e)) // never opened: nothing to spill, nothing to retry
        }
        val reopenable = try {
            openSource().use { }
            true
        } catch (_: Exception) {
            false
        }

        if (reopenable) {
            try {
                held.close()
            } catch (_: Exception) {
                // the probe source is unread scratch; a close failure must not fail the import
            }
            // Wrapped for the same reason as the spill path below: a lock can zeroize the session
            // mid-import, and a throw must not escape a function that promises an Outcome.
            return try {
                storeObject(kind, name, mime, chunkSize, openSource)
            } catch (e: Exception) {
                Outcome.Err(VaultError.Unexpected(e))
            }
        }

        // useSpilledCopy owns `held` once it starts copying — but it can throw before it gets there
        // (its scratch-name collision loop touches the filesystem first), and storeObject can throw
        // if the session dies mid-import. Either way the provider stream would be leaked and the
        // throw would escape a function whose contract is that no exception crosses the boundary.
        return try {
            storage
                .useSpilledCopy(held) { spilled -> storeObject(kind, name, mime, chunkSize, spilled) }
                .flatMap { it }
        } catch (e: Exception) {
            try {
                held.close()
            } catch (_: Exception) {
                // best-effort: report the failure that actually broke the import, not this one
            }
            Outcome.Err(VaultError.Unexpected(e))
        }
    }

    // Encrypt + record, given a source that can be opened as many times as the codec needs. Every
    // source handed to the codec is guarded, so a lock during a long import stops it within a chunk
    // rather than encrypting the rest of the file against a session the user already closed.
    private fun storeObject(
        kind: Int,
        name: String,
        mime: String,
        chunkSize: Int,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> = storeGuarded(kind, name, mime, chunkSize) {
        SessionGuardedSource(openSource()) { session.isOpen }
    }

    private fun storeGuarded(
        kind: Int,
        name: String,
        mime: String,
        chunkSize: Int,
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
        //    Sealing can also *throw* rather than return — a lock zeroizing the MK between the write
        //    and this line is the real case — and object bytes with no row and no DEK are
        //    unrecoverable garbage that would otherwise accumulate, so clean up before rethrowing.
        val encMeta = try {
            session.sealMeta(encodeMeta(ObjectMeta(name, mime)))
        } catch (e: Exception) {
            storage.deleteObject(fileId) // best-effort; no orphan ciphertext without a row
            throw e
        }
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

    /**
     * The list-view metadata ({name, mime}) for object [id], read from the catalog row's `enc_meta`
     * **without touching the object store** — cheap enough for a gallery listing. An unknown id →
     * `VaultError.Io(NoSuchElementException)`; a tampered `enc_meta` → the codec error from
     * [VaultSession.openMeta] (`IntegrityFailure` / `MalformedContainer`).
     */
    fun openMeta(id: ByteArray): Outcome<ObjectMeta> {
        val row = when (val got = catalog.getItem(id)) {
            is Outcome.Err -> return got
            is Outcome.Ok -> got.value ?: return absent(id)
        }
        return readMeta(row.enc_meta)
    }

    /**
     * The listing feeding a gallery: each catalog row joined to its decrypted [ObjectMeta], so
     * features consume [VaultItemSummary] and never a SQLDelight row type. Re-emits whenever the
     * catalog changes (an import, a shred), so callers never refresh by hand. Pass [albumId] to
     * narrow to one album, or `null` for everything.
     *
     * A row whose `enc_meta` will not open is **listed with the error**, never dropped — omitting it
     * would hide tampering, and its plaintext columns are still usable (§4). Only the metadata is
     * decrypted here; object bytes are never touched, which is what makes this cheap enough to run
     * over a whole vault.
     *
     * The flow inherits `CatalogStore.observeItems`' contract: a driver failure surfaces as an
     * exception **in the flow**, not an `Outcome`, so the collector is responsible for catching it
     * (the gallery component maps it to an error state). Swallowing it here would turn a broken
     * catalog into a silently empty vault, which is exactly the fail-closed rule's target.
     */
    fun observeItems(albumId: ByteArray? = null): Flow<List<VaultItemSummary>> =
        catalog.observeItems(albumId).map { rows -> rows.map(::toSummary) }

    private fun toSummary(row: Items): VaultItemSummary =
        VaultItemSummary(
            id = row.id,
            kind = VaultItemKind.fromCode(row.kind),
            size = row.size,
            createdAt = row.created_at,
            meta = readMeta(row.enc_meta),
        )

    // Unseal + decode a row's enc_meta. A malformed plaintext (post-unseal) is a container problem,
    // not an integrity one — the GCM tag already verified, so the bytes are ours but unreadable.
    private fun readMeta(encMeta: ByteArray): Outcome<ObjectMeta> =
        session.openMeta(encMeta).flatMap { bytes ->
            try {
                Outcome.Ok(decodeMeta(bytes))
            } catch (e: Exception) {
                Outcome.Err(VaultError.Crypto.MalformedContainer)
            }
        }

    /**
     * Crypto-shred object [id]: delete the catalog row **first** (that destroys `dek_wrapped`, and the
     * shred is effective the instant the wrapped DEK is gone), then best-effort delete the object
     * bytes. Orphaned ciphertext without a DEK is unrecoverable, so a failed byte-delete does **not**
     * un-shred the data — the shred is `Ok` once the row is gone. An unknown id →
     * `VaultError.Io(NoSuchElementException)`.
     */
    fun shredObject(id: ByteArray): Outcome<Unit> {
        when (val got = catalog.getItem(id)) {
            is Outcome.Err -> return got
            is Outcome.Ok -> if (got.value == null) return absent(id)
        }
        return catalog.deleteItem(id).map {
            storage.deleteObject(id) // best-effort; the DEK is already gone, so ignore any failure
            Unit
        }
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
