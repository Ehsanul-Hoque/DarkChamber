package dev.ehsanfahad.darkchamber.app.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The Media3 data source over a vault reader, on the JVM: Media3's data-source types are plain Java.
 * `open(DataSpec)` itself needs an Android `Uri` and is a three-line wrapper; what it wraps — where an
 * open starts and how far it reads — is [VaultDataSource.start], tested here.
 */
@UnstableApi
class VaultDataSourceTest {
    /** Every byte says where it sits, so a read from the wrong place shows. */
    private val content = ByteArray(1_000) { (it * 7 + 3).toByte() }

    /**
     * A reader that hands back at most [chunk] bytes a read, the way the vault's stops at a chunk
     * boundary; fails from [failFrom] on; and notices two reads at once.
     */
    private inner class ScriptedReader(
        private val chunk: Int = 64,
        private val failFrom: Long = Long.MAX_VALUE,
        private val error: VaultError = VaultError.Crypto.IntegrityFailure,
        private val dwellMs: Long = 0,
    ) : RangeReader {
        val inFlight = AtomicInteger()
        val overlapped = AtomicBoolean(false)
        var closes = 0
        override val length: Long = content.size.toLong()
        override val chunksDecrypted: Long = 17L

        override fun readAt(position: Long, into: ByteArray, offset: Int, size: Int): Outcome<Int> {
            if (inFlight.incrementAndGet() > 1) overlapped.set(true)
            try {
                if (dwellMs > 0) Thread.sleep(dwellMs)
                if (position >= failFrom) return Outcome.Err(error)
                if (position >= length) return Outcome.Ok(RangeReader.END)
                val inChunk = (chunk - (position % chunk)).toInt()
                val n = minOf(size, inChunk, (length - position).toInt())
                content.copyInto(into, offset, position.toInt(), position.toInt() + n)
                return Outcome.Ok(n)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun close() {
            closes++
        }
    }

    /** Reads until the source says the input has ended, in reads of [step]. */
    private fun VaultDataSource.readAll(step: Int = 100): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(step)
        while (true) {
            val n = read(buffer, 0, step)
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(buffer, 0, n)
        }
    }

    // ---- opening and reading -------------------------------------------------------------------

    @Test
    fun an_open_from_the_start_reads_the_whole_object_across_chunk_boundaries_then_ends() {
        val source = VaultDataSource(SharedRangeReader(ScriptedReader()))

        assertEquals(1_000L, source.start(position = 0, length = C.LENGTH_UNSET.toLong()))
        assertContentEquals(content, source.readAll())
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10), "and stays ended")
    }

    @Test
    fun an_open_with_a_range_reads_exactly_that_range() {
        val source = VaultDataSource(SharedRangeReader(ScriptedReader()))

        assertEquals(150L, source.start(position = 300, length = 150))
        assertContentEquals(content.copyOfRange(300, 450), source.readAll(step = 37))
    }

    @Test
    fun an_open_part_way_with_no_length_reads_to_the_end() {
        val source = VaultDataSource(SharedRangeReader(ScriptedReader()))

        assertEquals(400L, source.start(position = 600, length = C.LENGTH_UNSET.toLong()))
        assertContentEquals(content.copyOfRange(600, 1_000), source.readAll())
    }

    @Test
    fun a_range_running_past_the_end_is_cut_at_the_end() {
        val source = VaultDataSource(SharedRangeReader(ScriptedReader()))

        assertEquals(100L, source.start(position = 900, length = 500))
        assertContentEquals(content.copyOfRange(900, 1_000), source.readAll())
    }

    @Test
    fun an_open_at_the_end_has_nothing_to_read() {
        val source = VaultDataSource(SharedRangeReader(ScriptedReader()))

        assertEquals(0L, source.start(position = 1_000, length = C.LENGTH_UNSET.toLong()))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
    }

    @Test
    fun an_open_past_the_end_is_the_position_out_of_range_error() {
        val source = VaultDataSource(SharedRangeReader(ScriptedReader()))

        val thrown = assertFailsWith<DataSourceException> { source.start(position = 1_001, length = C.LENGTH_UNSET.toLong()) }
        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, thrown.reason)
    }

    @Test
    fun a_read_of_nothing_is_nothing_and_moves_nothing() {
        val source = VaultDataSource(SharedRangeReader(ScriptedReader()))
        source.start(position = 0, length = C.LENGTH_UNSET.toLong())

        assertEquals(0, source.read(ByteArray(10), 0, 0))
        assertContentEquals(content, source.readAll())
        assertEquals(0, source.read(ByteArray(10), 0, 0), "nothing asked is nothing, even at the end")
    }

    @Test
    fun a_read_lands_at_the_offset_it_was_given() {
        val source = VaultDataSource(SharedRangeReader(ScriptedReader()))
        source.start(position = 10, length = 5)
        val buffer = ByteArray(8)

        assertEquals(5, source.read(buffer, 3, 5))
        assertContentEquals(byteArrayOf(0, 0, 0) + content.copyOfRange(10, 15), buffer)
    }

    @Test
    fun the_reader_reaching_its_end_early_ends_the_input() {
        // A reader that answers END before the length it reported: the source ends, it does not spin.
        val shared = SharedRangeReader(object : RangeReader {
            override val length = 1_000L
            override fun readAt(position: Long, into: ByteArray, offset: Int, size: Int) = Outcome.Ok(RangeReader.END)
            override fun close() = Unit
        })
        val source = VaultDataSource(shared)
        source.start(position = 0, length = C.LENGTH_UNSET.toLong())

        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
    }

    @Test
    fun the_readers_end_moves_nothing_so_a_later_read_starts_where_it_was() {
        // END and the end-of-input result are the same number; what must not happen is the source
        // counting it as bytes read and stepping back.
        var first = true
        val reader = ScriptedReader()
        val shared = SharedRangeReader(object : RangeReader by reader {
            override fun readAt(position: Long, into: ByteArray, offset: Int, size: Int): Outcome<Int> =
                if (first) Outcome.Ok(RangeReader.END).also { first = false } else reader.readAt(position, into, offset, size)
        })
        val source = VaultDataSource(shared)
        source.start(position = 100, length = 10)

        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
        val buffer = ByteArray(10)
        assertEquals(10, source.read(buffer, 0, 10))
        assertContentEquals(content.copyOfRange(100, 110), buffer)
    }

    // ---- failures --------------------------------------------------------------------------------

    @Test
    fun a_refused_read_is_an_IOException_carrying_the_vaults_error_and_the_error_is_kept() {
        val shared = SharedRangeReader(ScriptedReader(failFrom = 128))
        val source = VaultDataSource(shared)
        source.start(position = 0, length = C.LENGTH_UNSET.toLong())

        assertEquals(64, source.read(ByteArray(100), 0, 100))
        assertEquals(64, source.read(ByteArray(100), 0, 100))
        val thrown = assertFailsWith<VaultReadException> { source.read(ByteArray(100), 0, 100) }

        assertSame(VaultError.Crypto.IntegrityFailure, thrown.error)
        assertSame(VaultError.Crypto.IntegrityFailure, shared.failure, "kept for the player's error to be traced to")
    }

    @Test
    fun no_failure_is_kept_while_every_read_succeeds() {
        val shared = SharedRangeReader(ScriptedReader())
        VaultDataSource(shared).apply { start(0, C.LENGTH_UNSET.toLong()) }.readAll()

        assertNull(shared.failure)
    }

    @Test
    fun the_first_failure_is_the_one_kept() {
        var error: VaultError = VaultError.Crypto.IntegrityFailure
        val shared = SharedRangeReader(object : RangeReader {
            override val length = 1_000L
            override fun readAt(position: Long, into: ByteArray, offset: Int, size: Int) = Outcome.Err(error)
            override fun close() = Unit
        })
        val source = VaultDataSource(shared)
        source.start(position = 0, length = C.LENGTH_UNSET.toLong())

        assertFailsWith<VaultReadException> { source.read(ByteArray(10), 0, 10) }
        error = VaultError.Io(RuntimeException("later"))
        assertFailsWith<VaultReadException> { source.read(ByteArray(10), 0, 10) }

        assertSame(VaultError.Crypto.IntegrityFailure, shared.failure)
    }

    // ---- closing ---------------------------------------------------------------------------------

    @Test
    fun a_source_closing_after_its_load_leaves_the_reader_open_for_the_next() {
        val reader = ScriptedReader()
        val shared = SharedRangeReader(reader)
        val first = VaultDataSource(shared)
        first.start(0, 100)
        first.close()

        assertEquals(0, reader.closes)
        val second = VaultDataSource(shared)
        second.start(500, 100)
        assertContentEquals(content.copyOfRange(500, 600), second.readAll())
    }

    @Test
    fun the_shared_reader_closes_its_reader_once_however_often_it_is_closed() {
        val reader = ScriptedReader()
        val shared = SharedRangeReader(reader)

        shared.close()
        shared.close()

        assertEquals(1, reader.closes)
    }

    @Test
    fun a_read_after_the_player_is_let_go_stops_the_load_and_is_not_a_failure() {
        val shared = SharedRangeReader(ScriptedReader())
        val source = VaultDataSource(shared)
        source.start(0, C.LENGTH_UNSET.toLong())
        shared.close()

        assertFailsWith<IOException> { source.read(ByteArray(10), 0, 10) }
        assertNull(shared.failure, "nothing failed: the player was let go")
    }

    // ---- sharing ---------------------------------------------------------------------------------

    @Test
    fun the_factory_makes_sources_that_all_read_the_one_reader() {
        val shared = SharedRangeReader(ScriptedReader())
        val a = shared.factory().createDataSource() as VaultDataSource
        val b = shared.factory().createDataSource() as VaultDataSource
        a.start(0, 10)
        b.start(990, 10)

        assertContentEquals(content.copyOfRange(0, 10), a.readAll())
        assertContentEquals(content.copyOfRange(990, 1_000), b.readAll())
    }

    @Test
    fun two_sources_reading_at_once_never_overlap_in_the_reader() {
        val reader = ScriptedReader(dwellMs = 1)
        val shared = SharedRangeReader(reader)
        val go = CountDownLatch(1)
        val readers = (0 until 2).map { n ->
            thread {
                val source = VaultDataSource(shared)
                source.start(position = n * 500L, length = 500)
                go.await()
                source.readAll(step = 16)
            }
        }
        go.countDown()
        readers.forEach { it.join() }

        assertFalse(reader.overlapped.get(), "the reader serves one caller at a time")
    }

    @Test
    fun the_decrypt_count_is_the_readers_own() {
        assertEquals(17L, SharedRangeReader(ScriptedReader()).chunksDecrypted)
    }
}
