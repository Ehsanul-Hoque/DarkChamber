package dev.ehsanfahad.darkchamber.core.storage

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path

/**
 * The shared blob primitive backing both the object store (sharded) and the thumbnail store
 * (flat). Pure I/O over opaque bytes — it never sees ciphers (spec D6); `.dchm`/`.tdchm`
 * content streams through the scoped [write]/[read] blocks as a [RawSink]/[RawSource].
 *
 * Every operation returns an [Outcome]; any failure is mapped to [VaultError.Io] and no
 * exception crosses the boundary (spec D7). Nothing here logs a path, id, or content (#4).
 *
 * @param baseDir the store root (e.g. `root/objects` or `root/thumbs`).
 * @param extension on-disk suffix including the dot (`.dchm` / `.tdchm`).
 * @param shard when true, files nest under `baseDir/hex[0:2]/hex[2:4]/`; when false they sit
 *   flat under `baseDir/`.
 */
internal class BlobStore(
    private val fs: FileSystem,
    private val baseDir: Path,
    private val extension: String,
    private val shard: Boolean,
) {
    /**
     * Scoped atomic write: opens a sink on a sibling `.tmp`, runs [block] against it, then on
     * success `atomicMove`s it onto the target (never overwrites in place). Any throw / early
     * return deletes the `.tmp` and surfaces `Err(Io)` — the target (if any) is left intact.
     */
    fun write(fileId: ByteArray, block: (RawSink) -> Unit): Outcome<Unit> {
        if (fileId.isEmpty()) return emptyIdErr()
        val target: Path
        val tmp: Path
        try {
            target = pathFor(fileId)
            tmp = Path(target.parent!!, "${target.name}.tmp")
            fs.createDirectories(target.parent!!)
        } catch (e: Exception) {
            return Outcome.Err(VaultError.Io(e))
        }
        return try {
            fs.sink(tmp).use { block(it) }
            fs.atomicMove(tmp, target)
            Outcome.Ok(Unit)
        } catch (e: Exception) {
            try {
                fs.delete(tmp, mustExist = false)
            } catch (_: Exception) {
                // best-effort cleanup — the original failure is what we report
            }
            Outcome.Err(VaultError.Io(e))
        }
    }

    /** Scoped read: opens a source, runs [block], closes in a `finally`. Missing file → `Err(Io)`. */
    fun <R> read(fileId: ByteArray, block: (RawSource) -> R): Outcome<R> {
        if (fileId.isEmpty()) return emptyIdErr()
        return try {
            fs.source(pathFor(fileId)).use { Outcome.Ok(block(it)) }
        } catch (e: Exception) {
            Outcome.Err(VaultError.Io(e))
        }
    }

    /** Removes the blob; idempotent (an absent id → `Ok`). */
    fun delete(fileId: ByteArray): Outcome<Unit> {
        if (fileId.isEmpty()) return emptyIdErr()
        return try {
            fs.delete(pathFor(fileId), mustExist = false)
            Outcome.Ok(Unit)
        } catch (e: Exception) {
            Outcome.Err(VaultError.Io(e))
        }
    }

    /** Whether a blob exists for [fileId]. An empty id (or any I/O failure) → false. */
    fun exists(fileId: ByteArray): Boolean {
        if (fileId.isEmpty()) return false
        return try {
            fs.metadataOrNull(pathFor(fileId))?.isRegularFile == true
        } catch (_: Exception) {
            false
        }
    }

    // hex is the safe on-disk charset over the id (no traversal); first 2 bytes → ab/cd shards (D7).
    private fun pathFor(fileId: ByteArray): Path {
        val hex = fileId.toHexString()
        return if (shard) {
            Path(baseDir, hex.substring(0, 2), hex.substring(2, 4), "$hex$extension")
        } else {
            Path(baseDir, "$hex$extension")
        }
    }

    // The cause carries no vault content — the id/path is never placed in the message (#4).
    private fun emptyIdErr(): Outcome.Err =
        Outcome.Err(VaultError.Io(IllegalArgumentException("empty fileId")))
}
