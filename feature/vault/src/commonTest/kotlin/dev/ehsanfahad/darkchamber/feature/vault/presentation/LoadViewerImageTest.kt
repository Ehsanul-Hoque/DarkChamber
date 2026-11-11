package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.writeBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Loading one image for the viewer: what is read, when the decoder is allowed to see it, and what is
 * left of our copy of the plaintext afterwards.
 *
 * The decoder is a fake that answers a string, so the loader's rules are tested without a platform
 * bitmap; the object read is a fake that writes whatever a case needs into the sink.
 */
class LoadViewerImageTest {
    private val picture = byteArrayOf(1, 2, 3, 4, 5, 6)

    private fun item(
        size: Long = picture.size.toLong(),
        meta: GalleryItemMeta = GalleryItemMeta.Readable(name = "x.jpg", mime = "image/jpeg"),
    ) = GalleryItem(
        id = byteArrayOf(9, 9),
        kind = VaultItemKind.FILE,
        size = size,
        createdAt = 0L,
        meta = meta,
    )

    /** A decoder that remembers what it was handed — the array itself, and its contents at the time. */
    private class RecordingDecoder(private val answer: String? = "bitmap") {
        var calls = 0
        var received: ByteArray? = null
        var contentsWhenCalled: ByteArray? = null
        var target: Int? = null

        fun decode(bytes: ByteArray, targetLongEdge: Int): String? {
            calls++
            received = bytes
            contentsWhenCalled = bytes.copyOf()
            target = targetLongEdge
            return answer
        }
    }

    /** Every buffer the loader asked for — the only way to see our copy of a read the decoder never got. */
    private val buffers = mutableListOf<ByteArray>()

    private fun TestScope.loader(
        items: FakeVaultItems,
        decoder: RecordingDecoder,
        cap: Long = 1_000,
    ) = LoadViewerImage(
        items = items,
        decode = decoder::decode,
        io = UnconfinedTestDispatcher(testScheduler),
        cap = cap,
        newBuffer = { size -> ByteArray(size).also { buffers += it } },
    )

    private fun assertAllZero(bytes: ByteArray?, what: String) {
        assertNotNull(bytes, "$what: no buffer to check")
        assertTrue(bytes.all { it == 0.toByte() }, "$what: still holds ${bytes.count { it != 0.toByte() }} plaintext bytes")
    }

    /**
     * The point of the rule: a chunk is released to the sink as soon as *it* verifies, so the first
     * part of a tampered object really does arrive. It must never reach the decoder.
     */
    @Test
    fun a_read_that_fails_after_some_chunks_arrived_is_never_decoded_and_is_zeroed() = runTest {
        val items = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture.copyOfRange(0, 4))
            Outcome.Err(VaultError.Crypto.IntegrityFailure)
        })
        val decoder = RecordingDecoder()

        val loaded = loader(items, decoder).load(item(), targetLongEdge = 4_800)

        assertEquals(LoadedPage.Page(ViewerPage.Unverified), loaded)
        assertEquals(0, decoder.calls)
        assertEquals(1, buffers.size)
        assertAllZero(buffers.single(), "after a failed read")
    }

    @Test
    fun each_failed_read_gets_the_page_for_its_error() = runTest {
        val cases = listOf(
            VaultError.Crypto.MalformedContainer to ViewerPage.Unverified,
            VaultError.Crypto.UnsupportedFormatVersion to ViewerPage.NeedsUpdate,
            VaultError.Io(RuntimeException("disk")) to ViewerPage.CouldntOpen,
        )
        for ((error, page) in cases) {
            val items = FakeVaultItems(onOpen = { _, _ -> Outcome.Err(error) })
            assertEquals(LoadedPage.Page(page), loader(items, RecordingDecoder()).load(item(), 4_800), "$error")
        }
    }

    /** Our copy is cleared once the decoder has made its own; the platform's buffers are its own. */
    @Test
    fun the_buffer_is_zeroed_once_the_decoder_returns() = runTest {
        val items = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture)
            Outcome.Ok(Unit)
        })
        val decoder = RecordingDecoder()

        loader(items, decoder).load(item(), targetLongEdge = 4_800)

        assertContentEquals(picture, decoder.contentsWhenCalled, "the decoder saw the plaintext")
        assertAllZero(decoder.received, "after decoding")
    }

    @Test
    fun a_declined_image_shows_the_cant_show_page_and_is_zeroed() = runTest {
        val items = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture)
            Outcome.Ok(Unit)
        })
        val decoder = RecordingDecoder(answer = null)

        val loaded = loader(items, decoder).load(item(), targetLongEdge = 4_800)

        assertEquals(LoadedPage.Page(ViewerPage.CantShow), loaded)
        assertAllZero(decoder.received, "after a decline")
    }

    /** The seam promises never to throw; a platform that breaks the promise still gets a page, and a cleared buffer. */
    @Test
    fun a_decoder_that_throws_is_treated_as_declining() = runTest {
        val items = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture)
            Outcome.Ok(Unit)
        })
        val loaded = LoadViewerImage<String>(
            items = items,
            decode = { _, _ -> throw IllegalArgumentException("unsupported") },
            io = UnconfinedTestDispatcher(testScheduler),
            newBuffer = { size -> ByteArray(size).also { buffers += it } },
        ).load(item(), targetLongEdge = 4_800)

        assertEquals(LoadedPage.Page(ViewerPage.CantShow), loaded)
        assertAllZero(buffers.single(), "after a throwing decoder")
    }

    /**
     * The read is sized from the catalog's plaintext size. If the object turns out longer, the
     * extra is refused rather than grown into; if shorter, the tail would be zeros the decoder
     * mistakes for picture. Either way the row and the object disagree, and nothing is drawn.
     */
    @Test
    fun an_object_that_disagrees_with_its_recorded_size_is_not_decoded() = runTest {
        val longer = FakeVaultItems(onOpen = { _, sink ->
            // As the codec does: a sink that refuses a write ends the read as an Io failure.
            try {
                sink.writeBytes(picture)
                Outcome.Ok(Unit)
            } catch (e: IOException) {
                Outcome.Err(VaultError.Io(e))
            }
        })
        val decoder = RecordingDecoder()
        assertEquals(
            LoadedPage.Page(ViewerPage.CouldntOpen),
            loader(longer, decoder).load(item(size = 4), 4_800),
            "longer than recorded",
        )

        val shorter = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture.copyOfRange(0, 4))
            Outcome.Ok(Unit)
        })
        assertEquals(
            LoadedPage.Page(ViewerPage.CouldntOpen),
            loader(shorter, decoder).load(item(size = 6), 4_800),
            "shorter than recorded",
        )
        assertEquals(0, decoder.calls)
        buffers.forEachIndexed { i, b -> assertAllZero(b, "buffer $i") }
    }

    @Test
    fun a_verified_image_is_decoded_once_from_all_of_its_bytes_at_the_target_size() = runTest {
        val items = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture.copyOfRange(0, 4)) // the first chunk…
            sink.writeBytes(picture.copyOfRange(4, 6)) // …and the last
            Outcome.Ok(Unit)
        })
        val decoder = RecordingDecoder()

        val loaded = loader(items, decoder).load(item(), targetLongEdge = 4_800)

        assertEquals("bitmap", assertIs<LoadedPage.Shown<String>>(loaded).image)
        assertEquals(1, decoder.calls)
        assertContentEquals(picture, decoder.contentsWhenCalled)
        assertEquals(4_800, decoder.target)
    }

    /** The read and the decode both block for as long as a large photo takes, so neither may run on the caller's thread. */
    @Test
    fun the_read_and_the_decode_run_on_the_injected_dispatcher() = runTest {
        val io = ManualDispatcher()
        var readOnIo = false
        var decodedOnIo = false
        val items = FakeVaultItems(onOpen = { _, sink ->
            readOnIo = io.isRunning
            sink.writeBytes(picture)
            Outcome.Ok(Unit)
        })
        val loader = LoadViewerImage(
            items = items,
            decode = { _, _ ->
                decodedOnIo = io.isRunning
                "bitmap"
            },
            io = io,
        )
        var loaded: LoadedPage<String>? = null

        launch { loaded = loader.load(item(), targetLongEdge = 4_800) }
        runCurrent()
        assertEquals(0, items.openCalls, "nothing read before the dispatcher ran")
        io.drain()
        advanceUntilIdle()

        assertIs<LoadedPage.Shown<String>>(loaded)
        assertTrue(readOnIo, "read on the injected dispatcher")
        assertTrue(decodedOnIo, "decoded on the injected dispatcher")
    }

    /**
     * A lock cancels the viewer's work and then closes the session. A read already under way runs
     * to its end — it is blocking — but what it read is dropped: no decode, no page, our copy zeroed.
     */
    @Test
    fun a_load_cancelled_during_the_read_decodes_nothing_and_publishes_nothing() = runTest {
        lateinit var job: Job
        val items = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture)
            job.cancel() // the lock arrives while the object is being read
            Outcome.Ok(Unit)
        })
        val decoder = RecordingDecoder()
        var loaded: LoadedPage<String>? = null

        job = launch { loaded = loader(items, decoder).load(item(), targetLongEdge = 4_800) }
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertNull(loaded, "no page published")
        assertEquals(0, decoder.calls, "nothing decoded")
        assertAllZero(buffers.single(), "after a cancelled read")
    }

    /** A cancellation surfacing from inside the read is a cancellation, not a page saying the file failed. */
    @Test
    fun a_cancellation_thrown_by_the_read_stays_a_cancellation() = runTest {
        val items = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture)
            throw CancellationException("torn down")
        })

        assertFailsWith<CancellationException> { loader(items, RecordingDecoder()).load(item(), 4_800) }
        assertAllZero(buffers.single(), "after a cancelled read")
    }

    /**
     * The vault returns outcomes rather than throwing, with one exception: a session closed under a
     * read throws. A load is never left without a page by that, and our copy is still cleared.
     */
    @Test
    fun a_read_that_throws_gets_the_couldnt_open_page_and_is_zeroed() = runTest {
        val items = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture.copyOfRange(0, 4))
            throw IllegalStateException("vault session is closed")
        })
        val decoder = RecordingDecoder()

        val loaded = loader(items, decoder).load(item(), targetLongEdge = 4_800)

        assertEquals(LoadedPage.Page(ViewerPage.CouldntOpen), loaded)
        assertEquals(0, decoder.calls)
        assertAllZero(buffers.single(), "after a read that threw")
    }

    /**
     * Twice the viewport's long edge: sharp at fit and through the first zoom steps, while a 48 MP
     * photo still decodes to a fraction of its full size. The long edge, so turning the phone does
     * not change what is decoded.
     */
    @Test
    fun the_decode_target_is_twice_the_viewports_long_edge() {
        assertEquals(4_800, decodeTarget(viewportWidthPx = 1_080, viewportHeightPx = 2_400), "portrait")
        assertEquals(4_800, decodeTarget(viewportWidthPx = 2_400, viewportHeightPx = 1_080), "landscape")
        assertEquals(1_500, decodeTarget(viewportWidthPx = 750, viewportHeightPx = 750), "square")
    }

    /** Before the viewport is measured there is no size to aim for; asking is a caller bug, not a 0-px decode. */
    @Test
    fun an_unmeasured_viewport_has_no_decode_target() {
        assertFailsWith<IllegalArgumentException> { decodeTarget(0, 2_400) }
        assertFailsWith<IllegalArgumentException> { decodeTarget(1_080, 0) }
    }

    @Test
    fun a_kind_with_no_viewer_yet_reads_nothing() = runTest {
        val items = FakeVaultItems(onOpen = { _, _ -> error("must not be read") })
        val decoder = RecordingDecoder()

        val loaded = loader(items, decoder).load(
            item(meta = GalleryItemMeta.Readable(name = "x.mp4", mime = "video/mp4")),
            targetLongEdge = 4_800,
        )

        assertEquals(LoadedPage.Page(ViewerPage.NotViewable("VID")), loaded)
        assertEquals(0, items.openCalls)
        assertEquals(0, decoder.calls)
    }

    /** The size is a plaintext column, so a file too big to hold is refused before a byte is read. */
    @Test
    fun an_image_over_the_cap_reads_nothing() = runTest {
        val items = FakeVaultItems(onOpen = { _, _ -> error("must not be read") })
        val decoder = RecordingDecoder()

        val loaded = loader(items, decoder, cap = 1_000).load(item(size = 1_001), targetLongEdge = 4_800)

        assertEquals(LoadedPage.Page(ViewerPage.TooLarge), loaded)
        assertEquals(0, items.openCalls)
        assertEquals(0, decoder.calls)
    }

    /** Only a tampered catalog records a negative size; it gets a page, not a crash, and nothing is read. */
    @Test
    fun a_negative_recorded_size_reads_nothing() = runTest {
        val items = FakeVaultItems(onOpen = { _, _ -> error("must not be read") })
        val decoder = RecordingDecoder()

        val loaded = loader(items, decoder).load(item(size = -1), targetLongEdge = 4_800)

        assertEquals(LoadedPage.Page(ViewerPage.CouldntOpen), loaded)
        assertEquals(0, items.openCalls)
        assertEquals(0, buffers.size)
    }

    /**
     * An image within the cap can still be more than the heap has free. Running out while allocating
     * the read is a page for that file, not the end of the app, and nothing is read.
     */
    @Test
    fun running_out_of_memory_for_the_read_gets_the_too_large_page() = runTest {
        val items = FakeVaultItems(onOpen = { _, _ -> error("must not be read") })
        val loaded = LoadViewerImage<String>(
            items = items,
            decode = { _, _ -> error("must not be decoded") },
            io = UnconfinedTestDispatcher(testScheduler),
            newBuffer = { throw OutOfMemoryError("no room") },
        ).load(item(), targetLongEdge = 4_800)

        assertEquals(LoadedPage.Page(ViewerPage.TooLarge), loaded)
        assertEquals(0, items.openCalls)
    }

    /** A decoder that runs out of memory gets the same page, and our copy is still cleared. */
    @Test
    fun running_out_of_memory_while_decoding_gets_the_too_large_page() = runTest {
        val items = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture)
            Outcome.Ok(Unit)
        })
        val loaded = LoadViewerImage<String>(
            items = items,
            decode = { _, _ -> throw OutOfMemoryError("no room") },
            io = UnconfinedTestDispatcher(testScheduler),
            newBuffer = { size -> ByteArray(size).also { buffers += it } },
        ).load(item(), targetLongEdge = 4_800)

        assertEquals(LoadedPage.Page(ViewerPage.TooLarge), loaded)
        assertAllZero(buffers.single(), "after a decoder that ran out of memory")
    }

    /**
     * Each load holds a buffer the size of its file, up to the cap, and the viewer asks for three pages
     * at once. One at a time keeps the peak at one file: the second load is not handed to the
     * dispatcher until the first has finished.
     */
    @Test
    fun loads_run_one_at_a_time() = runTest {
        val io = ManualDispatcher()
        val items = FakeVaultItems(onOpen = { _, sink ->
            sink.writeBytes(picture)
            Outcome.Ok(Unit)
        })
        val loader = LoadViewerImage(items = items, decode = { _, _ -> "bitmap" }, io = io)
        val loaded = mutableListOf<LoadedPage<String>>()

        launch { loaded += loader.load(item(), targetLongEdge = 4_800) }
        launch { loaded += loader.load(item(), targetLongEdge = 4_800) }
        try {
            runCurrent()
            assertEquals(1, io.pending, "only the first load is running")

            io.runNext()
            runCurrent()
            assertEquals(1, loaded.size, "the first has finished")
            assertEquals(1, io.pending, "and only then does the second start")

            io.runNext()
            runCurrent()
            assertEquals(2, loaded.size)
        } finally {
            // A failed assertion must not leave a load parked on the manual dispatcher, or the test
            // waits for it for ever instead of reporting.
            io.drain()
            advanceUntilIdle()
        }
    }
}
