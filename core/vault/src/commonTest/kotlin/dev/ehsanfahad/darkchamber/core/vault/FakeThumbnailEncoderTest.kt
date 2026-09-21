package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract test over the fake seam. Everything downstream — the import path's "a thumbnail may not
 * happen" behaviour, the read-back, the gallery fallback — is tested through this double, so the
 * four outcomes it can be configured to produce have to be pinned before anything relies on them.
 *
 * The outcomes are not arbitrary: they are the four things a real decoder does. It succeeds; it
 * declines a type it does not handle; it "succeeds" with nothing usable (a truncated encode, a
 * zero-byte write); or it blows up on a corrupt file. The third and fourth are the ones a cheaper
 * fake would leave untested.
 */
class FakeThumbnailEncoderTest {
    /** A source that reports whether it was read to exhaustion and whether it was closed. */
    private class TrackingSource(private val bytes: ByteArray) : RawSource {
        private var pos = 0
        var closed: Boolean = false
            private set

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (pos >= bytes.size) return -1L
            val n = minOf(byteCount, (bytes.size - pos).toLong()).toInt()
            sink.write(bytes, pos, pos + n)
            pos += n
            return n.toLong()
        }

        override fun close() {
            closed = true
        }
    }

    private val payload = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun encode_returns_the_configured_bytes_and_records_the_request() {
        val thumbnail = byteArrayOf(9, 8, 7)
        val encoder = FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode, thumbnail)
        val source = TrackingSource(payload)

        val result = encoder.encode("image/jpeg", maxEdgePx = 256) { source }

        assertContentEquals(thumbnail, result)
        assertEquals(1, encoder.encodeCount)
        assertEquals("image/jpeg", encoder.lastMime)
        assertEquals(256, encoder.lastMaxEdgePx)
        // The plaintext actually reached the encoder — a fake that ignored the source would let a
        // caller pass a source it never opens and still see a thumbnail come back.
        assertContentEquals(payload, encoder.lastSource)
        assertTrue(source.closed, "the fake must close what it opens")
    }

    @Test
    fun decline_returns_null_for_a_type_the_encoder_does_not_handle() {
        val encoder = FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Decline)

        val result = encoder.encode("application/zip", maxEdgePx = 256) { TrackingSource(payload) }

        assertNull(result)
        assertEquals(1, encoder.encodeCount)
    }

    @Test
    fun empty_returns_a_zero_length_array_rather_than_null() {
        val encoder = FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Empty)

        val result = encoder.encode("image/png", maxEdgePx = 256) { TrackingSource(payload) }

        // Distinct from Decline on purpose: an encoder that hands back nothing usable while
        // claiming success is a different bug from one that admits it cannot help, and the import
        // path has to survive both. assertNotNull first, so a null result fails here rather than
        // passing an emptiness check by accident.
        assertNotNull(result)
        assertEquals(0, result.size)
    }

    @Test
    fun throw_propagates_so_the_caller_has_to_contain_it() {
        val encoder = FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Throw)
        val source = TrackingSource(payload)

        assertFailsWith<IllegalStateException> {
            encoder.encode("image/jpeg", maxEdgePx = 256) { source }
        }

        // The throw comes after the read, so a caller that leaks the source on the failure path is
        // visible as an unclosed source rather than being masked by the fake never opening it.
        assertTrue(source.closed, "the fake must close what it opens, including when it throws")
        assertEquals(1, encoder.encodeCount)
    }

    @Test
    fun the_count_and_the_last_request_track_across_calls() {
        val encoder = FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode)

        encoder.encode("image/jpeg", maxEdgePx = 128) { TrackingSource(payload) }
        encoder.encode("image/heic", maxEdgePx = 512) { TrackingSource(byteArrayOf(42)) }

        assertEquals(2, encoder.encodeCount)
        assertEquals("image/heic", encoder.lastMime)
        assertEquals(512, encoder.lastMaxEdgePx)
        assertContentEquals(byteArrayOf(42), encoder.lastSource)
    }
}
