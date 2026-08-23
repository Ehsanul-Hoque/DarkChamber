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
 * (no `core:crypto` dependency; the codec composes over storage at the call site, spec D6).
 *
 * **Precondition (D1/D3):** [root] is an app-provided, backup-excluded directory. Platform
 * root-resolution (Android `filesDir` / iOS `Documents`) and the iOS `isExcludedFromBackup`
 * runtime call are the app cycle's job (cycle 5); this facade takes the resolved root as an
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

    // The encrypted object store: sharded objects/ab/cd/<hexId>.dchm (D7).
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

    /** Deletes the object blob (idempotent). Authoritative shred is destroying the DEK (cycle 4). */
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
}
