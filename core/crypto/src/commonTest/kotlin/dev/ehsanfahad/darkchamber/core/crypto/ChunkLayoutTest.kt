package dev.ehsanfahad.darkchamber.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Where each chunk of a container sits. Every expected number is worked out by hand from FORMAT.md
 * §2 — a 16-byte prelude, then `header_len` bytes of sealed header, then each chunk's ciphertext
 * followed by its 16-byte tag — never computed by the code under test. The three committed positive
 * vectors (`docs/format-test-vectors/aes-256-gcm/positive.json`) supply the `header_len`s, read from
 * their preludes, and the totals below equal those containers' real lengths (229, 289 and 220 bytes).
 */
class ChunkLayoutTest {

    @Test
    fun single_chunk_vector_is_one_short_chunk_straight_after_the_header() {
        // 11 bytes at chunk size 16, header_len 186: 16 + 186 = 202, then 11 + 16 = 27 → 229.
        val layout = ChunkLayout(headerLen = 186, chunkSize = 16, plaintextSize = 11)

        assertEquals(1L, layout.chunkCount)
        assertEquals(202L, layout.chunkOffset(0))
        assertEquals(27, layout.storedChunkLength(0))
        assertEquals(229L, layout.containerLength)
    }

    @Test
    fun multi_chunk_vector_has_two_full_chunks_and_a_short_last_one() {
        // 40 bytes at chunk size 16 → 16 + 16 + 8, header_len 185: chunks at 201, 201 + 32 = 233 and
        // 233 + 32 = 265, stored 32, 32 and 8 + 16 = 24 → 265 + 24 = 289.
        val layout = ChunkLayout(headerLen = 185, chunkSize = 16, plaintextSize = 40)

        assertEquals(3L, layout.chunkCount)
        assertEquals(listOf(201L, 233L, 265L), (0L..2L).map { layout.chunkOffset(it) })
        assertEquals(listOf(32, 32, 24), (0L..2L).map { layout.storedChunkLength(it) })
        assertEquals(289L, layout.containerLength)
    }

    @Test
    fun empty_vector_is_exactly_one_empty_chunk_which_is_only_its_tag() {
        // FORMAT.md §2.3: an empty object is one empty final chunk. header_len 188: 16 + 188 = 204,
        // stored 0 + 16 = 16 → 220.
        val layout = ChunkLayout(headerLen = 188, chunkSize = 16, plaintextSize = 0)

        assertEquals(1L, layout.chunkCount)
        assertEquals(204L, layout.chunkOffset(0))
        assertEquals(16, layout.storedChunkLength(0))
        assertEquals(220L, layout.containerLength)
    }

    @Test
    fun a_size_that_is_an_exact_multiple_ends_on_a_full_chunk_with_no_empty_one_after_it() {
        // No committed vector has this shape, and it is where an off-by-one in the last chunk hides.
        // 32 bytes at chunk size 16 → exactly 16 + 16: two chunks, the last one full, no empty third.
        // header_len 186: chunks at 202 and 202 + 32 = 234, both stored 32 → 234 + 32 = 266.
        val layout = ChunkLayout(headerLen = 186, chunkSize = 16, plaintextSize = 32)

        assertEquals(2L, layout.chunkCount)
        assertEquals(listOf(202L, 234L), (0L..1L).map { layout.chunkOffset(it) })
        assertEquals(listOf(32, 32), (0L..1L).map { layout.storedChunkLength(it) })
        assertEquals(266L, layout.containerLength)
    }

    @Test
    fun a_chunk_index_outside_the_container_is_refused() {
        val layout = ChunkLayout(headerLen = 185, chunkSize = 16, plaintextSize = 40)

        for (index in listOf(-1L, 3L)) {
            assertFailsWith<IllegalArgumentException> { layout.chunkOffset(index) }
            assertFailsWith<IllegalArgumentException> { layout.storedChunkLength(index) }
        }
    }

    @Test
    fun numbers_no_container_can_have_are_refused() {
        assertFailsWith<IllegalArgumentException> { ChunkLayout(headerLen = 185, chunkSize = 0, plaintextSize = 40) }
        assertFailsWith<IllegalArgumentException> { ChunkLayout(headerLen = 185, chunkSize = 16, plaintextSize = -1) }
        assertFailsWith<IllegalArgumentException> { ChunkLayout(headerLen = -1, chunkSize = 16, plaintextSize = 40) }
        assertFailsWith<IllegalArgumentException> {
            ChunkLayout(headerLen = DchmFormat.MAX_HEADER_LEN + 1, chunkSize = 16, plaintextSize = 40)
        }
    }
}
