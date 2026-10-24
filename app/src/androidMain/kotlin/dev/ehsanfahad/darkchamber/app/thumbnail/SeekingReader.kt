package dev.ehsanfahad.darkchamber.app.thumbnail

import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered

/**
 * Random reads over a source that can only be read forwards: a read ahead of the current position
 * skips forward on the open stream, and a read behind it opens the source again and skips from the
 * start. That is all a frame grabber needs to read a video the vault hands over as a stream.
 *
 * **The recently read blocks are kept**, up to [maxBlocks] of [blockSize] bytes. A grabber reads an
 * MP4's index, then a frame, then the index again; where the index is at the end of the file — as
 * every phone's recorder writes it — each return to it would otherwise be one more read through the
 * whole file. Measured on a 1.1 GB clip, that was 14 opens and 8.2 GB read for one preview.
 *
 * A skip still reads every byte it passes, so [bytesRead] counts them; it is what a preview costs.
 * The kept blocks are plaintext in memory only — nothing is written anywhere — and are zeroed when
 * dropped and on [close].
 *
 * Not thread-safe; a frame grabber reads from one thread.
 */
internal class SeekingReader(
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
    private val openSource: () -> RawSource,
) : AutoCloseable {
    private var source: Source? = null
    private var position = 0L

    // By block index, the one used longest ago first.
    private val blocks = object : LinkedHashMap<Long, ByteArray>(maxBlocks + 1, 1f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean =
            (size > maxBlocks).also { if (it) eldest.value.fill(0) }
    }

    /** Every byte pulled from the source so far, skipped bytes included. */
    var bytesRead: Long = 0
        private set

    /**
     * Reads up to [size] bytes at [at] into [into] from [offset], never past the end of the block
     * [at] falls in; the count, or -1 at or past the end of the file.
     */
    fun readAt(at: Long, into: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        val index = at / blockSize
        val block = blocks[index] ?: load(index) ?: return -1
        val from = (at - index * blockSize).toInt()
        if (from >= block.size) return -1
        val n = minOf(size, block.size - from)
        block.copyInto(into, offset, from, from + n)
        return n
    }

    override fun close() {
        source?.close()
        source = null
        blocks.values.forEach { it.fill(0) }
        blocks.clear()
    }

    // The block at [index] read from the source and kept, or null when it starts past the end.
    private fun load(index: Long): ByteArray? {
        val stream = positionedAt(index * blockSize) ?: return null
        val block = ByteArray(blockSize)
        var got = 0
        while (got < blockSize) {
            val n = stream.readAtMostTo(block, got, blockSize)
            if (n < 0) break
            got += n
        }
        position += got
        bytesRead += got
        if (got == 0) return null
        val kept = if (got == blockSize) block else block.copyOf(got).also { block.fill(0) }
        blocks[index] = kept
        return kept
    }

    // The stream at [at], re-opened when [at] is behind it; null when [at] is past the end.
    private fun positionedAt(at: Long): Source? {
        if (source == null || at < position) {
            source?.close()
            source = openSource().buffered()
            position = 0
        }
        val stream = source ?: return null
        // In bounded steps: a jump to a video's index can be the length of the video.
        while (position < at) {
            val step = minOf(at - position, discard.size.toLong()).toInt()
            val n = stream.readAtMostTo(discard, 0, step)
            if (n < 0) return null
            position += n
            bytesRead += n
        }
        return stream
    }

    // Where skipped bytes are read to and dropped. Allocated once, on the first skip.
    private val discard by lazy { ByteArray(SKIP_STEP_BYTES) }

    private companion object {
        const val SKIP_STEP_BYTES = 64 * 1024
        const val DEFAULT_BLOCK_SIZE = 64 * 1024

        // 8 MiB: an hour of 4K at 30 fps has an index of a megabyte or two.
        const val DEFAULT_MAX_BLOCKS = 128
    }
}
