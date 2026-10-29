package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.OpenedContainer
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.flatMap
import dev.ehsanfahad.darkchamber.core.storage.ObjectReader

/**
 * Any byte range of a vault object's plaintext, decrypting only the chunks the range touches — what
 * a video's frame grabber, and later a player, needs from an object several gigabytes long.
 *
 * Every read returns an [Outcome]; nothing throws across it. Not thread-safe: one reader serves one
 * caller.
 */
interface RangeReader : AutoCloseable {
    /** The object's plaintext size in bytes, known at open without reading the object through. */
    val length: Long

    /**
     * Copies up to [size] bytes of plaintext from [position] into [into] at [offset] and returns how
     * many it copied, or [END] when [position] is at or past [length]. A read may return fewer bytes
     * than asked — at a chunk boundary, for one — as both platforms' readers allow.
     */
    fun readAt(position: Long, into: ByteArray, offset: Int, size: Int): Outcome<Int>

    override fun close()

    companion object {
        /** What [readAt] returns at or past the end of the object. */
        const val END: Int = -1
    }
}

internal class ChunkRangeReader private constructor(
    private val session: VaultSession,
    private val codec: DchmCodec,
    private val fileId: ByteArray,
    private val dekWrapped: ByteArray,
    private val file: ObjectReader,
    opened: OpenedContainer,
    // Polled before every read: the session is open and the work behind the reader still wants it.
    private val stillWanted: () -> Boolean,
) : RangeReader {
    private var closed = false
    private val noncePrefix = opened.noncePrefix
    private val layout = opened.layout

    // Decrypted chunks by index, least recently used first. At most two: a read that straddles a
    // boundary needs both halves, and a frame grabber that goes back and forth between an index and a
    // frame would otherwise decrypt one of them again on every return. Plaintext, so a chunk is zeroed
    // the moment it is dropped. The open's last chunk is the first one held — the end of a video is
    // where its index usually is, and the first place a grabber goes.
    private val held = LinkedHashMap<Long, ByteArray>().apply { put(layout.chunkCount - 1, opened.lastChunk) }

    override val length: Long = layout.plaintextSize

    override fun readAt(position: Long, into: ByteArray, offset: Int, size: Int): Outcome<Int> {
        if (closed) return Outcome.Err(VaultError.Io(IllegalStateException("reader is closed")))
        // Checked before the held chunks are looked at: after a lock, plaintext already in memory must
        // not be handed out either. Nothing held survives the refusal.
        if (!stillWanted()) {
            release()
            return Outcome.Err(VaultError.Io(OperationStoppedException()))
        }
        if (position >= length) return Outcome.Ok(RangeReader.END)
        val index = position / layout.chunkSize
        val chunk = held.remove(index) ?: when (val decrypted = decrypt(index)) {
            is Outcome.Err -> return decrypted
            is Outcome.Ok -> decrypted.value
        }
        held[index] = chunk // re-inserted, so it is now the most recently used
        while (held.size > MAX_HELD) {
            val eldest = held.keys.first()
            held.remove(eldest)?.fill(0)
        }
        val from = (position - index * layout.chunkSize).toInt()
        val n = minOf(size, chunk.size - from)
        chunk.copyInto(into, destinationOffset = offset, startIndex = from, endIndex = from + n)
        return Outcome.Ok(n)
    }

    override fun close() {
        if (closed) return
        closed = true
        release()
        file.close()
    }

    /** The plaintext chunks held right now, oldest first — the arrays themselves, for tests. */
    internal fun heldChunks(): List<ByteArray> = held.values.toList()

    private fun release() {
        for (chunk in held.values) chunk.fill(0)
        held.clear()
    }

    private fun decrypt(index: Long): Outcome<ByteArray> {
        val stored = when (val read = file.readFully(layout.chunkOffset(index), layout.storedChunkLength(index))) {
            is Outcome.Err -> return read
            is Outcome.Ok -> read.value
        }
        // The key is unwrapped for this one chunk and zeroed by the session as soon as it is used, so
        // no raw key lives in the reader between reads.
        return try {
            session.useDek(dekWrapped) { dek ->
                codec.decryptChunk(dek, noncePrefix, fileId, index, isLast = index == layout.chunkCount - 1, stored)
            }.flatMap { it }
        } catch (e: IllegalStateException) {
            // A lock landed after the check above: the session refuses by throwing.
            release()
            Outcome.Err(VaultError.Io(OperationStoppedException()))
        }
    }

    companion object {
        private const val MAX_HELD = 2

        /** Opens a reader over object [fileId] whose key is [dekWrapped]; closes the file on any failure. */
        fun open(
            session: VaultSession,
            codec: DchmCodec,
            file: ObjectReader,
            fileId: ByteArray,
            dekWrapped: ByteArray,
            stillWanted: () -> Boolean,
        ): Outcome<RangeReader> {
            val opened = try {
                if (!stillWanted()) throw IllegalStateException("stopped before the open")
                session.useDek(dekWrapped) { dek ->
                    codec.openLayout(dek, fileId, file.size) { position, length ->
                        when (val read = file.readFully(position, length)) {
                            is Outcome.Err -> throw ReadFailed(read.error)
                            is Outcome.Ok -> read.value
                        }
                    }
                }.flatMap { it }
            } catch (e: IllegalStateException) {
                Outcome.Err(VaultError.Io(OperationStoppedException()))
            }
            return when (opened) {
                is Outcome.Err -> {
                    file.close()
                    Outcome.Err(opened.error.unwrapReadFailure())
                }
                is Outcome.Ok -> Outcome.Ok(ChunkRangeReader(session, codec, fileId, dekWrapped, file, opened.value, stillWanted))
            }
        }
    }
}

/** Whether [this] says the object's bytes are not what was written, as opposed to a read that did not happen. */
internal fun VaultError.isDamage(): Boolean =
    this == VaultError.Crypto.IntegrityFailure || this == VaultError.Crypto.MalformedContainer

/**
 * A [RangeReader] that remembers the first damage error any read returned, so the caller learns of it
 * even when whoever was reading — a platform frame grabber — swallowed it.
 */
internal class DamageWatchingReader(private val delegate: RangeReader) : RangeReader by delegate {
    var damage: VaultError? = null
        private set

    override fun readAt(position: Long, into: ByteArray, offset: Int, size: Int): Outcome<Int> =
        delegate.readAt(position, into, offset, size).also { read ->
            if (read is Outcome.Err && damage == null && read.error.isDamage()) damage = read.error
        }
}

// Carries a storage read's own error out through the codec's read callback, which can only throw.
private class ReadFailed(val error: VaultError) : Exception()

// The codec reports a throwing read as Io(cause); give back the storage error the read actually had.
private fun VaultError.unwrapReadFailure(): VaultError = ((this as? VaultError.Io)?.cause as? ReadFailed)?.error ?: this

/** Exactly [length] bytes at [position], looping over short reads; running out early is an `Io`. */
private fun ObjectReader.readFully(position: Long, length: Int): Outcome<ByteArray> {
    val out = ByteArray(length)
    var got = 0
    while (got < length) {
        when (val read = readAt(position + got, out, got, length - got)) {
            is Outcome.Err -> return read
            is Outcome.Ok -> {
                if (read.value == ObjectReader.END) {
                    return Outcome.Err(VaultError.Io(IllegalStateException("object ended early")))
                }
                got += read.value
            }
        }
    }
    return Outcome.Ok(out)
}
