package dev.ehsanfahad.darkchamber.core.storage

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.files.Path

/**
 * A stored object opened for reads at any position, from [VaultStorage.openObjectReader]. It hands
 * back the object's bytes as stored — `.dchm` ciphertext; nothing here decrypts, and nothing here
 * writes.
 *
 * Reading a 1 MiB chunk deep inside a multi-gigabyte video should cost that chunk, not every byte
 * before it, and kotlinx-io's sources only go forwards. So the read goes to the platform's own
 * positioned read — `FileChannel` on Android, `pread` on iOS — behind [openPlatformFile].
 *
 * Every failure comes back as `Err(Io)`; nothing throws across it. Not thread-safe: one reader
 * serves one caller.
 */
class ObjectReader internal constructor(private val file: PlatformFile) : AutoCloseable {
    private var closed = false

    /** The object's size in bytes, as stored. */
    val size: Long get() = file.size

    /**
     * Copies up to [length] bytes from [position] into [into] at [offset] and returns how many it
     * copied, or `-1` when [position] is at or past the end. A read may return fewer bytes than
     * asked, as the platform's own read may; a caller that needs them all loops.
     */
    fun readAt(position: Long, into: ByteArray, offset: Int, length: Int): Outcome<Int> {
        // Refused here, not left to the platform: a closed POSIX descriptor's number is reused by the
        // next open, so a read through it would return some other file's bytes as if they were ours.
        if (closed) return Outcome.Err(VaultError.Io(IllegalStateException("reader is closed")))
        // Decided here rather than by the platform: at the end `FileChannel` answers -1 and `pread` 0.
        if (position >= size) return Outcome.Ok(END)
        return try {
            Outcome.Ok(file.read(position, into, offset, length))
        } catch (e: Exception) {
            Outcome.Err(VaultError.Io(e))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        file.close()
    }

    companion object {
        /** What [readAt] returns at or past the end of the object. */
        const val END = -1
    }
}

/** A file opened for positioned reads, as the platform provides them. Its methods may throw. */
internal interface PlatformFile {
    val size: Long

    /** As [ObjectReader.readAt], without the `Outcome`. */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int

    fun close()
}

/** Opens [path] read-only for positioned reads; throws when it cannot. */
internal expect fun openPlatformFile(path: Path): PlatformFile
