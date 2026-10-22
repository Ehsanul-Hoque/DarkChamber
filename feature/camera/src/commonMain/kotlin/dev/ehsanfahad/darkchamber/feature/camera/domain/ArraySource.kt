package dev.ehsanfahad.darkchamber.feature.camera.domain

import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * A source factory over [bytes] that the vault's import can open as often as it likes, each source
 * independent of the others and reading from the start.
 *
 * Re-openability is what keeps a capture off disk: the import probes for it by holding one source
 * open and asking for a second, and only a source that refuses is copied into app-private scratch
 * first. Every source here reads [bytes] **in place** — rather than from a `Buffer` filled up front,
 * which would hold a second complete copy of the photo in pooled segments that are recycled without
 * being cleared — so the caller's array stays the one buffer it has to zero.
 */
internal fun reopenableSource(bytes: ByteArray): () -> RawSource = { ArraySource(bytes) }

private class ArraySource(private val bytes: ByteArray) : RawSource {
    private var position = 0
    private var closed = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "source is closed" }
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (position == bytes.size) return -1L
        val count = minOf(byteCount, (bytes.size - position).toLong()).toInt()
        sink.write(bytes, position, position + count)
        position += count
        return count.toLong()
    }

    override fun close() {
        closed = true
    }
}
