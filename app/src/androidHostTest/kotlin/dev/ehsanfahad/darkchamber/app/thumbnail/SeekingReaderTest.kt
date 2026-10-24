package dev.ehsanfahad.darkchamber.app.thumbnail

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Random reads over a source that can only be read forwards and re-opened — how a frame grabber that
 * wants to seek reads a video the vault hands over as a stream. Small blocks here so the file spans
 * many; in use they are 64 KiB.
 */
class SeekingReaderTest {
    private val file = ByteArray(1000) { (it % 251).toByte() }

    private var opens = 0
    private val open = mutableListOf<Tracked>()
    private val reader = SeekingReader(blockSize = 100, maxBlocks = 3) {
        opens++
        Tracked(Buffer().apply { write(file) }).also { open += it }
    }

    // Reads until [size] bytes or the end, as a frame grabber does with short answers.
    private fun read(position: Long, size: Int): ByteArray? {
        val into = ByteArray(size)
        var got = 0
        while (got < size) {
            val n = reader.readAt(position + got, into, got, size - got)
            if (n < 0) break
            got += n
        }
        return if (got == 0) null else into.copyOf(got)
    }

    @Test
    fun reads_in_order_come_from_one_open() {
        assertContentEquals(file.copyOfRange(0, 150), read(0, 150))
        assertContentEquals(file.copyOfRange(150, 420), read(150, 270))
        assertEquals(1, opens)
    }

    /** A jump forward skips on the stream it has; nothing is opened again. */
    @Test
    fun a_jump_forward_skips_without_reopening() {
        read(0, 10)
        assertContentEquals(file.copyOfRange(900, 950), read(900, 50))
        assertEquals(1, opens)
        assertEquals(1000L, reader.bytesRead, "the skipped bytes are read too, and whole blocks are read")
    }

    /**
     * The point of the blocks: a grabber reading a video's index at the end, then a frame near the
     * start, then the index again, goes back to the end without reading the file through again.
     */
    @Test
    fun a_block_read_recently_is_read_again_from_memory() {
        read(950, 20) // the index
        read(10, 20) // a frame: behind, so one re-open
        assertEquals(2, opens)
        val before = reader.bytesRead

        assertContentEquals(file.copyOfRange(955, 975), read(955, 20))
        assertEquals(2, opens, "the index again, with no re-open")
        // Without the blocks this is a skip forward through the file, which re-opens nothing but
        // reads everything in between — the cost the blocks exist to remove.
        assertEquals(before, reader.bytesRead, "and nothing read from the source")
    }

    /** Only so many blocks are kept: the one used longest ago goes first. */
    @Test
    fun the_block_used_longest_ago_is_dropped_first() {
        read(900, 10) // block 9
        read(0, 10) // block 0, re-open
        read(100, 10) // block 1
        read(900, 10) // block 9 again, from memory, and now the most recent
        read(200, 10) // block 2: block 0 goes
        assertEquals(2, opens)
        val before = reader.bytesRead

        read(950, 10) // block 9, still kept
        assertEquals(before, reader.bytesRead, "block 9 from memory: used more recently than block 0")
        read(0, 10) // block 0, gone: re-open
        assertEquals(3, opens)
    }

    /** A read behind the position, not in memory, opens the source again and closes the old one. */
    @Test
    fun a_read_behind_reopens_from_the_start() {
        read(500, 10)
        assertContentEquals(file.copyOfRange(20, 40), read(20, 20))
        assertEquals(2, opens)
        assertTrue(open[0].closed, "the stream left behind is closed")
    }

    @Test
    fun the_end_reads_short_and_then_nothing() {
        assertContentEquals(file.copyOfRange(990, 1000), read(990, 50), "what is left, not padding")
        assertEquals(null, read(1000, 10), "at the end")
        assertEquals(null, read(5000, 10), "past the end")
    }

    @Test
    fun close_closes_the_open_stream() {
        read(0, 10)
        reader.close()
        assertTrue(open.single().closed)
    }

    private class Tracked(private val inner: RawSource) : RawSource {
        var closed = false
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = inner.readAtMostTo(sink, byteCount)
        override fun close() {
            closed = true
            inner.close()
        }
    }
}
