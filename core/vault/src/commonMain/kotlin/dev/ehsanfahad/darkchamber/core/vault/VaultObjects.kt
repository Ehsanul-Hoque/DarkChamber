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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

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
    // Where the listing's per-row metadata decrypt runs. Injected so tests stay deterministic; the
    // default keeps that crypto off whatever thread a caller collects on.
    private val mapContext: CoroutineContext = Dispatchers.Default,
    // Decodes and downscales a preview during import. Injected from `:app` with the platform
    // encoder; the default produces none, so an import behaves exactly as it did before thumbnails.
    private val thumbnails: ThumbnailEncoder = NoThumbnails,
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
     *
     * [onProgress] receives an [ImportPhase] and the **cumulative** count of bytes read within that
     * phase. [ImportPhase.ENCRYPTING] covers both of the codec's passes as one monotonic run from 0
     * to `2 × size`, because the two-pass read is the container's business and not the user's.
     *
     * The phase is reported because the denominator differs between them and only this module knows
     * which one applies — whether the source had to be spilled is decided in here. The caller still
     * owns the denominator, the percentage, and the decision of what to draw when no size is known:
     * [ImportPhase] says which arithmetic to do, never what to show.
     *
     * [isActive] lets a caller abandon an import that is still running. It is polled on every read,
     * alongside the session's own state, and returning `false` aborts within a read or two — the
     * encrypt is blocking, so cancelling the coroutine around it would not stop it, and a caller
     * that waited for the loop to notice would wait out the rest of the file. The abort takes the
     * same fail-closed path a lock does (no object bytes, no row, no scratch) and leaves the session
     * open and usable, so cancelling one file costs only that file.
     */
    fun importObject(
        kind: Int,
        name: String,
        mime: String,
        chunkSize: Int = DchmFormat.DEFAULT_CHUNK_SIZE,
        onProgress: (ImportPhase, Long) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
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
                storeObject(kind, name, mime, chunkSize, onProgress, isActive, openSource)
            } catch (e: Exception) {
                Outcome.Err(VaultError.Unexpected(e))
            }
        }

        // The copy's own counter, separate from the encrypt's by construction rather than by being
        // reset. One counter shared across the two would open the encrypt phase at the payload size,
        // and a caller dividing by `2 × size` would draw a bar that begins half full.
        var copied = 0L

        // useSpilledCopy owns `held` once it starts copying — but it can throw before it gets there
        // (its scratch-name collision loop touches the filesystem first), and storeObject can throw
        // if the session dies mid-import. Either way the provider stream would be leaked and the
        // throw would escape a function whose contract is that no exception crosses the boundary.
        return try {
            storage
                // Guarded like every other read of this payload. The copy is a straight drain with
                // no chunk loop to notice anything, so without this it is the one stretch of an
                // import that a lock or a cancel cannot stop — and lock's teardown purges `tmp/`
                // *before* the copy would finish, so an unguarded drain goes on writing plaintext
                // into an already-wiped directory after the master key is gone. Aborting here throws
                // out of the copy, and `useSpilledCopy` deletes the half-written scratch on its way
                // out.
                //
                // Counted, and only on this path: a re-openable source is never copied, so reporting
                // the phase for it would put a label on work that is not happening. For a provider
                // that streams, this drain *is* the transfer — the guard is nested inside so a stopped
                // copy reports nothing further, the same ordering the encrypt phase uses.
                .useSpilledCopy(
                    SessionGuardedSource(
                        ProgressSource(held) { n ->
                            copied += n
                            onProgress(ImportPhase.COPYING, copied)
                        },
                    ) { session.isOpen && isActive() },
                ) { spilled ->
                    storeObject(kind, name, mime, chunkSize, onProgress, isActive, spilled)
                }
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
    // rather than encrypting the rest of the file against a session the user already closed. The
    // caller's own reason to stop rides the same check: one predicate, one abort path, and a cancel
    // cleans up exactly the way a lock already does.
    private fun storeObject(
        kind: Int,
        name: String,
        mime: String,
        chunkSize: Int,
        onProgress: (ImportPhase, Long) -> Unit,
        isActive: () -> Boolean,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> {
        // One counter for this phase, declared here rather than inside the opener: the codec opens a
        // fresh source per pass, so a per-source counter would restart halfway and the caller would
        // watch the same bytes arrive twice. It starts at zero even when a copy preceded it — the
        // phases divide by different numbers, so a count carried across the boundary would open this
        // one at the payload size.
        var processed = 0L
        val stillWanted = { session.isOpen && isActive() }
        return storeGuarded(
            kind,
            name,
            mime,
            chunkSize,
            openSource = {
                SessionGuardedSource(
                    ProgressSource(openSource()) { n ->
                        processed += n
                        onProgress(ImportPhase.ENCRYPTING, processed)
                    },
                    stillWanted,
                )
            },
            // Guarded like every other read — a lock must stop a decode mid-file — but deliberately
            // **not** counted. How many times the encoder passes over the payload is its own
            // business, so there is no denominator these bytes could be drawn against, and counting
            // them would push the encrypt phase past `2 × size`.
            openForThumbnail = { SessionGuardedSource(openSource(), stillWanted) },
            // What is reported instead is that the attempt is happening, once, with no bytes. It
            // replaces the bar sitting full while the encoder works — the reported total is reached
            // when the payload is sealed, and everything after it used to be silent.
            onPreviewAttempt = { onProgress(ImportPhase.PREVIEWING, 0L) },
        )
    }

    private fun storeGuarded(
        kind: Int,
        name: String,
        mime: String,
        chunkSize: Int,
        openSource: () -> RawSource,
        openForThumbnail: () -> RawSource,
        // Called once, immediately before the encoder is asked. A lambda rather than a phase value
        // so this function keeps knowing nothing about progress vocabulary: it says *when*, and the
        // caller decides what that is worth reporting.
        onPreviewAttempt: () -> Unit = {},
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

        // 2. Generate the preview from the plaintext, before the row is written, so the row is
        //    written **once** with its final metadata. Reading it back from the stored object
        //    instead would mean decrypting the whole file: the random-access seek primitive does
        //    not exist yet. Returns null — and leaves nothing behind — for everything that is not
        //    an image the platform decoder accepts, which is the ordinary case for most of a vault.
        onPreviewAttempt()
        val thumbnailKey = makeThumbnail(fileId, mime, createdAt, chunkSize, openForThumbnail)

        // 3. Seal the metadata and persist the row. Fail-closed: a row failure removes the object.
        //    Sealing can also *throw* rather than return — a lock zeroizing the MK between the write
        //    and this line is the real case — and object bytes with no row and no DEK are
        //    unrecoverable garbage that would otherwise accumulate, so clean up before rethrowing.
        val encMeta = try {
            session.sealMeta(encodeMeta(ObjectMeta(name, mime, thumbnailKey)))
        } catch (e: Exception) {
            storage.deleteObject(fileId) // best-effort; no orphan ciphertext without a row
            storage.deleteThumbnail(fileId) // and no preview of an object that no longer exists
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
                storage.deleteThumbnail(fileId) // the key lived in the row that never landed
                upsert
            }
        }
    }

    /**
     * Encode a preview of the payload and seal it into `thumbs/<id>.tdchm` under a **fresh** DEK,
     * returning that DEK wrapped under MK for the row's metadata — or `null` when there is no
     * thumbnail to store.
     *
     * **Nothing in here can fail an import.** A thumbnail is a cache: an unsupported type, a corrupt
     * file, a decoder that throws, a disk error writing the blob — every one of them leaves the item
     * imported and simply without a preview. That is why this returns a nullable rather than an
     * `Outcome`: there is no failure for a caller to act on, and an error surfaced here would
     * eventually be rendered as though the user's file were damaged.
     *
     * The DEK is **wrapped**, not raw, even though `enc_meta` is itself sealed under MK. The listing
     * decodes `enc_meta` for every row it shows, so a raw key would put one plaintext DEK per item
     * into the gallery's state — reachable in memory long after a lock zeroized the master key.
     * Wrapped, the key is inert without an open session (hard rule #5), and it costs one unwrap at
     * the moment a tile is actually drawn.
     */
    private fun makeThumbnail(
        fileId: ByteArray,
        mime: String,
        createdAt: Long,
        chunkSize: Int,
        openForThumbnail: () -> RawSource,
    ): ByteArray? =
        try {
            val preview = thumbnails.encode(mime, THUMBNAIL_MAX_EDGE_PX, openForThumbnail)
            // Empty is treated as "no thumbnail", not as a zero-byte thumbnail: an encoder that
            // claims success while producing nothing would otherwise be sealed into a perfectly
            // valid container that opens and draws nothing, which reads as a corrupt image rather
            // than an item that never had a preview.
            if (preview == null || preview.isEmpty()) {
                null
            } else {
                sealThumbnail(fileId, createdAt, chunkSize, preview)
            }
        } catch (_: Exception) {
            // Includes the stop path: a lock or a cancel during the decode throws out of the guarded
            // source. The import that follows will fail on its own at the seal — this must not be
            // the thing that decides that.
            storage.deleteThumbnail(fileId) // best-effort; never leave a blob no row points at
            null
        }

    // Writes the preview as its own .dchm container — a fresh DEK and a fresh nonce_prefix, because
    // FORMAT.md §2 makes a per-object DEK mandatory and this is a second object in every sense the
    // format cares about. The header carries no name (the object's name is already in enc_meta;
    // copying decrypted content into a second container is a second place it can leak) and no MIME
    // (the encoder does not declare what it produced, and the image loader sniffs the bytes).
    private fun sealThumbnail(fileId: ByteArray, createdAt: Long, chunkSize: Int, preview: ByteArray): ByteArray? {
        val dekWrapped = session.generateDek()
        val noncePrefix = random(DchmFormat.NONCE_PREFIX_SIZE)
        val sealed = session.useDek(dekWrapped) { rawDek ->
            var enc: Outcome<Long>? = null
            val write = storage.writeThumbnail(fileId) { sink ->
                enc = codec.encryptContainer(
                    rawDek,
                    noncePrefix,
                    createdAt,
                    fileId,
                    originalName = "",
                    mime = "",
                    chunkSize = chunkSize,
                    openSource = { Buffer().apply { write(preview) } },
                    sink = sink,
                )
                if (enc is Outcome.Err) throw AbortWrite // abort the atomic move; storage deletes the temp
            }
            write is Outcome.Ok && enc is Outcome.Ok
        }
        return if (sealed is Outcome.Ok && sealed.value) {
            dekWrapped
        } else {
            storage.deleteThumbnail(fileId) // best-effort; a half-written preview is no preview
            null
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
     * Decrypt object [id]'s thumbnail **into memory** and return its bytes, or `null` when the item
     * has none. Whole-blob rather than streaming on purpose: a thumbnail is bounded by
     * [THUMBNAIL_MAX_EDGE_PX] and an image loader wants a byte array, so there is nothing to stream
     * into. The plaintext exists only in the returned array — it never reaches disk (hard rule #1).
     *
     * Three outcomes, and the difference between the first two is the point:
     *
     * - **`Ok(bytes)`** — the preview, exactly as the encoder produced it.
     * - **`Ok(null)`** — this item has no thumbnail. The ordinary case for most of a vault: every
     *   non-image, every file the decoder declined, everything imported before thumbnails existed,
     *   and a blob that has gone missing underneath us. Callers draw the type badge.
     * - **`Err`** — `Crypto.IntegrityFailure` when the stored blob fails its GCM tag, `Io` for an
     *   unknown id (as [openObject] and [openMeta] do), and whatever `enc_meta` failed with when the
     *   metadata itself will not open.
     *
     * A damaged thumbnail must **not** collapse into the absent case. Absent means "there was never
     * a preview here"; a failed tag means the bytes on disk were altered, which is a tampering
     * signal the project never silently swallows. A missing *file*, by contrast, proves nothing —
     * there is no tag to fail — so it reads as absent rather than as damage to an item whose own
     * bytes are perfectly intact.
     */
    fun openThumbnail(id: ByteArray): Outcome<ByteArray?> {
        val row = when (val got = catalog.getItem(id)) {
            is Outcome.Err -> return got
            is Outcome.Ok -> got.value ?: return absent(id)
        }
        val wrappedKey = when (val meta = readMeta(row.enc_meta)) {
            is Outcome.Err -> return meta
            is Outcome.Ok -> meta.value.thumbnailKey ?: return Outcome.Ok(null)
        }
        // Checked rather than inferred from a read failure: `readThumbnail` reports a missing file
        // as `Io`, which is indistinguishable from a disk that failed mid-read, and one of those is
        // an ordinary state while the other is worth surfacing.
        if (!storage.thumbnailExists(id)) return Outcome.Ok(null)

        val out = Buffer()
        return session.useDek(wrappedKey) { rawDek ->
            storage.readThumbnail(id) { src -> codec.decryptContainer(rawDek, id, src, out) }
        }.flatMap { it }.flatMap { it }.map { out.readByteArray() }
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
     * The per-row unseal is AES-GCM plus a CBOR decode, so it runs on [mapContext] rather than
     * wherever the caller happens to collect — a re-emission in a large vault is one GCM open per
     * row, and on a UI thread that is jank the collector cannot see coming. `flowOn` is applied
     * here, in the module that owns the crypto, so no consumer has to remember it; the collector's
     * own context still receives the emissions.
     *
     * The flow inherits `CatalogStore.observeItems`' contract: a driver failure surfaces as an
     * exception **in the flow**, not an `Outcome`, so the collector is responsible for catching it
     * (the gallery component maps it to an error state). Swallowing it here would turn a broken
     * catalog into a silently empty vault, which is exactly the fail-closed rule's target.
     */
    fun observeItems(albumId: ByteArray? = null): Flow<List<VaultItemSummary>> =
        catalog.observeItems(albumId)
            .map { rows -> rows.map(::toSummary) }
            .flowOn(mapContext)

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
            // The thumbnail's key lived in the row's enc_meta, so deleting the row already shredded
            // it — this only stops an unopenable blob sitting in `thumbs/` for the life of the
            // vault, invisible to every listing and therefore to everything that would clean it up.
            storage.deleteThumbnail(id)
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
