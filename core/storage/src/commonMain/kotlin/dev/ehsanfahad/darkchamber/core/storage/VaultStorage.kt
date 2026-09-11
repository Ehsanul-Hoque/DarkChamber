package dev.ehsanfahad.darkchamber.core.storage

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.random.Random
import kotlin.random.nextULong

/**
 * The app-private vault storage facade — the one module permitted to touch filesystem APIs
 * (docs/engineering/MODULES.md §core). Owns the on-disk layout (docs/ARCHITECTURE.md §4):
 *
 * ```
 * root/
 *  ├─ objects/ab/cd/<hexId>.dchm   object blob store  (sharded)
 *  ├─ thumbs/<hexId>.tdchm         thumbnail store    (flat)
 *  ├─ keys/mk.wrapped              wrapped-key blob
 *  └─ tmp/                         scratch            (purged on lock/launch)
 * ```
 *
 * `.dchm` bytes flow through here as opaque sources/sinks — storage never sees ciphers
 * (no `core:crypto` dependency; the codec composes over storage at the call site).
 *
 * **Precondition:** [root] is an app-provided, backup-excluded directory. Platform
 * root-resolution (Android `filesDir` / iOS `Documents`) and the iOS `isExcludedFromBackup`
 * runtime call live in the app module; this facade takes the resolved root as an
 * injected [Path], which makes the whole store host- and iOS-sim-testable against a temp root.
 *
 * @param root the resolved, backup-excluded vault directory.
 * @param fs the filesystem to operate on; defaults to [SystemFileSystem] (a param only so a
 *   test can substitute a fake — the real system filesystem on a temp root is the default path).
 */
class VaultStorage(
    private val root: Path,
    private val fs: FileSystem = SystemFileSystem,
) {
    init {
        // Ensure the four layout dirs exist (idempotent — mustCreate = false).
        fs.createDirectories(Path(root, "objects"))
        fs.createDirectories(Path(root, "thumbs"))
        fs.createDirectories(Path(root, "keys"))
        fs.createDirectories(Path(root, "tmp"))
    }

    // The encrypted object store: sharded objects/ab/cd/<hexId>.dchm.
    private val objects = BlobStore(fs, Path(root, "objects"), extension = ".dchm", shard = true)

    /**
     * Streams an encrypted object into `objects/ab/cd/<hexId>.dchm` atomically. The caller writes
     * `.dchm` bytes into [block]'s sink (e.g. `codec.encryptContainer(..., sink = it)`); on success
     * the temp file is `atomicMove`d onto the target, on any throw it is cleaned up and the prior
     * object (if any) is left intact.
     */
    fun writeObject(fileId: ByteArray, block: (RawSink) -> Unit): Outcome<Unit> =
        objects.write(fileId, block)

    /** Opens the encrypted object for reading; the source is closed when [block] returns. */
    fun <R> readObject(fileId: ByteArray, block: (RawSource) -> R): Outcome<R> =
        objects.read(fileId, block)

    /** Deletes the object blob (idempotent). Authoritative shred is destroying the DEK. */
    fun deleteObject(fileId: ByteArray): Outcome<Unit> = objects.delete(fileId)

    /** Whether an object blob exists for [fileId]. */
    fun objectExists(fileId: ByteArray): Boolean = objects.exists(fileId)

    // The encrypted thumbnail store: flat thumbs/<hexId>.tdchm — same atomic primitive, no shard.
    private val thumbnails = BlobStore(fs, Path(root, "thumbs"), extension = ".tdchm", shard = false)

    /**
     * Streams an encrypted thumbnail into `thumbs/<hexId>.tdchm` atomically. Same atomicity and
     * `Err(Io)` guarantees as [writeObject]; thumbnails are always encrypted `.tdchm` blobs (hard
     * rule #1 — no plaintext thumbnails anywhere).
     */
    fun writeThumbnail(fileId: ByteArray, block: (RawSink) -> Unit): Outcome<Unit> =
        thumbnails.write(fileId, block)

    /** Opens the encrypted thumbnail for reading; the source is closed when [block] returns. */
    fun <R> readThumbnail(fileId: ByteArray, block: (RawSource) -> R): Outcome<R> =
        thumbnails.read(fileId, block)

    /** Deletes the thumbnail blob (idempotent). */
    fun deleteThumbnail(fileId: ByteArray): Outcome<Unit> = thumbnails.delete(fileId)

    /** Whether a thumbnail blob exists for [fileId]. */
    fun thumbnailExists(fileId: ByteArray): Boolean = thumbnails.exists(fileId)

    // The wrapped-key blob — a single fixed-name whole file (the KeyStoreBlob CBOR bytes).
    private val keyBlobPath = Path(Path(root, "keys"), "mk.wrapped")

    /**
     * Reads the wrapped-key blob (`keys/mk.wrapped`). `Ok(null)` when the file is absent — a fresh
     * install with no vault yet — `Ok(bytes)` otherwise, `Err(Io)` on a real read error. The blob
     * is small (wrapped MK slots), so it is read whole.
     */
    fun readKeyStore(): Outcome<ByteArray?> =
        try {
            // metadataOrNull == null means the path is genuinely absent (no vault yet). A present
            // path that isn't a readable regular file (e.g. a directory) throws on source() below
            // and surfaces as Err(Io) — not silently masked as "no vault".
            if (fs.metadataOrNull(keyBlobPath) == null) {
                Outcome.Ok(null)
            } else {
                fs.source(keyBlobPath).use { Outcome.Ok(it.buffered().readByteArray()) }
            }
        } catch (e: Exception) {
            Outcome.Err(VaultError.Io(e))
        }

    /** Atomically writes the wrapped-key blob (temp sibling + `atomicMove`, never in place). */
    fun writeKeyStore(bytes: ByteArray): Outcome<Unit> {
        val tmp = Path(Path(root, "keys"), "mk.wrapped.tmp")
        return try {
            fs.sink(tmp).buffered().use { it.write(bytes) }
            fs.atomicMove(tmp, keyBlobPath)
            Outcome.Ok(Unit)
        } catch (e: Exception) {
            try {
                fs.delete(tmp, mustExist = false)
            } catch (_: Exception) {
                // best-effort cleanup — report the original failure
            }
            Outcome.Err(VaultError.Io(e))
        }
    }

    /**
     * Runs [block] with a fresh unique file under `tmp/`, then deletes that file in a `finally` —
     * on a normal return **and** when the block throws. This is the Android PDF-viewer
     * decrypt-to-disk path (docs/ARCHITECTURE.md §7): decrypt into the temp file, render, delete,
     * all within the scope so no plaintext outlives it (hard rule #1). The block's result (or its
     * exception) propagates unchanged.
     */
    fun <R> useTempFile(block: (Path) -> R): R {
        val tmpDir = Path(root, "tmp")
        var candidate = Path(tmpDir, "scratch-${nextTempToken()}")
        while (fs.exists(candidate)) {
            candidate = Path(tmpDir, "scratch-${nextTempToken()}")
        }
        try {
            return block(candidate)
        } finally {
            try {
                fs.delete(candidate, mustExist = false)
            } catch (_: Exception) {
                // best-effort — the scratch file is inside the wiped-on-lock tmp/ regardless
            }
        }
    }

    /**
     * Spills a **one-shot** [source] into `tmp/` and runs [block] with an opener that yields a fresh
     * source over that copy each time it is called. The scratch file is deleted when [block] returns
     * or throws ([useTempFile]'s guarantee), and [source] is consumed and closed here.
     *
     * This exists for import: the `.dchm` codec reads its payload twice, so a provider stream that
     * cannot be re-opened would otherwise be unusable. It is a **fallback**, never the default path —
     * a re-openable source must be imported straight through, without ever landing plaintext on
     * disk. The copy is app-private and inside `tmp/`, which is purged on lock, on launch, and after
     * each viewer session, so this stays within hard rule #1 rather than around it.
     *
     * A failure while **copying** maps to `Err(Io)`. [block]'s own exception propagates unchanged,
     * like [useTempFile] — reporting it as an `Io` would blame the spill for a caller's failure —
     * and the scratch file is deleted either way.
     */
    fun <R> useSpilledCopy(source: RawSource, block: (openSource: () -> RawSource) -> R): Outcome<R> =
        useTempFile { path ->
            try {
                source.use { src -> fs.sink(path).buffered().use { it.transferFrom(src) } }
            } catch (e: Exception) {
                return@useTempFile Outcome.Err(VaultError.Io(e))
            }
            Outcome.Ok(block { fs.source(path).buffered() })
        }

    /**
     * Deletes every entry under `tmp/` (and only `tmp/`), leaving the directory itself. Called by
     * the session/lock lifecycle on lock, app launch, and after each viewer session, so
     * no decrypted scratch survives a lock (hard rule #1).
     */
    fun purgeTmp(): Outcome<Unit> =
        try {
            val tmpDir = Path(root, "tmp")
            for (entry in fs.list(tmpDir)) deleteRecursively(entry)
            Outcome.Ok(Unit)
        } catch (e: Exception) {
            Outcome.Err(VaultError.Io(e))
        }

    private fun deleteRecursively(path: Path) {
        val md = fs.metadataOrNull(path) ?: return
        if (md.isDirectory) {
            for (child in fs.list(path)) deleteRecursively(child)
        }
        fs.delete(path, mustExist = false)
    }

    // Unique-enough scratch token; collision-checked against the dir before use.
    private fun nextTempToken(): String = Random.nextULong().toString()
}
