package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.FakePdfRenderer
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPageSize
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPlace
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.domain.GalleryControls
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The viewer showing a PDF over a fake renderer and a fake vault, every dispatcher injected. The listing,
 * newest first: 3 (b.pdf), 2 (photo), 1 (a.pdf) — so b.pdf is page 0 and a.pdf page 2.
 */
class ViewerPdfTest {
    private val lifecycle = LifecycleRegistry().apply { resume() }

    private fun id(n: Int) = byteArrayOf(n.toByte(), 0x5A)

    private fun key(n: Int) = id(n).toListKey()

    private val size = 2_000L

    private fun entry(n: Int, name: String, mime: String, bytes: Long = size): VaultItemSummary =
        summary(id(n), readableMeta(name, mime), size = bytes, createdAt = n * 10L)

    private var listing = listOf(
        entry(1, "a.pdf", "application/pdf"),
        entry(2, "photo.jpg", "image/jpeg"),
        entry(3, "b.pdf", "application/pdf"),
    )

    private val renderer = FakePdfRenderer()

    /** Ids an image load was asked for: a PDF must never be loaded as a picture. */
    private val loaded = mutableListOf<List<Byte>>()

    /** Runs inside the vault read, after part of the file is written — where a swipe or a lock can land mid-read. */
    private var duringRead: () -> Unit = {}

    /** What the vault read answers once it has written the whole file. */
    private var readAnswer: Outcome<Unit> = Outcome.Ok(Unit)

    private fun items() = FakeVaultItems(
        onOpen = { _: ByteArray, sink: RawSink ->
            // Not `size` inside `apply`: there it would be the Buffer's own.
            val half = size / 2
            sink.write(Buffer().apply { write(ByteArray(half.toInt())) }, half)
            duringRead()
            sink.write(Buffer().apply { write(ByteArray(half.toInt())) }, half)
            readAnswer
        },
    ).apply { summaries.value = listing }

    private fun TestScope.viewer(
        start: Int,
        items: FakeVaultItems = items(),
        pdfs: FakePdfRenderer? = renderer,
        pdfContext: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
        release: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
        io: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
    ) = ViewerComponent(
        componentContext = DefaultComponentContext(lifecycle),
        observeGallery = ObserveGallery(items),
        observeAlbum = ObserveAlbum(items),
        load = { item: GalleryItem, _: Int ->
            loaded += item.id.toList()
            LoadedPage.Page(ViewerPage.CantShow)
        },
        shred = { Outcome.Ok(Unit) },
        filter = ItemFilter.All,
        controls = GalleryControls(),
        startId = id(start),
        onBack = {},
        openRange = items::openRange,
        playerFactory = null,
        mainContext = UnconfinedTestDispatcher(testScheduler),
        ioContext = io,
        releaseContext = release,
        openObject = items::open,
        pdfs = pdfs,
        pdfContext = pdfContext,
    ).also { it.viewportMeasured(1_000, 2_000) }

    private fun ViewerComponent<*>.page(n: Int) = state.value.pages[key(n)]

    private val threePages = listOf(PdfPageSize(595, 842), PdfPageSize(595, 842), PdfPageSize(842, 595))

    // ---- opening ---------------------------------------------------------------------------------

    /** Read whole into the platform's buffer, and opened only after the vault said the whole file verified. */
    @Test
    fun a_pdf_shown_is_read_into_a_buffer_and_opened_only_after_it_verified() = runTest {
        val items = items()
        val viewer = viewer(start = 3, items = items)

        assertEquals(1, items.openCalls)
        assertEquals(listOf("buffer $size", "open $size"), renderer.log, "the whole file was in the buffer before it opened")
        assertEquals(ViewerPage.Pdf(3, threePages, PdfPlace()), viewer.page(3))
        assertTrue(loaded.none { it == id(3).toList() }, "a PDF is never loaded as a picture")
    }

    @Test
    fun a_read_the_vault_refused_closes_the_buffer_unopened_and_says_why() = runTest {
        readAnswer = Outcome.Err(VaultError.Crypto.IntegrityFailure)
        val viewer = viewer(start = 3)

        assertEquals(listOf("buffer $size", "close buffer"), renderer.log, "never opened, released at once")
        assertEquals(ViewerPage.Unverified, viewer.page(3))
    }

    @Test
    fun a_newer_format_asks_for_an_update_and_anything_else_names_no_cause() = runTest {
        readAnswer = Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion)
        assertEquals(ViewerPage.NeedsUpdate, viewer(start = 3).page(3))
        readAnswer = Outcome.Err(VaultError.Io(IllegalStateException("x")))
        assertEquals(ViewerPage.CouldntOpen, viewer(start = 1).page(1))
    }

    @Test
    fun a_verified_pdf_the_platform_refuses_shows_that_refusals_page() = runTest {
        renderer.refusal = PdfRefusal.NeedsPassword
        assertEquals(ViewerPage.PasswordProtected, viewer(start = 3).page(3))
        renderer.refusal = PdfRefusal.Unreadable
        assertEquals(ViewerPage.Unreadable, viewer(start = 1).page(1))
    }

    @Test
    fun a_platform_that_cannot_show_pdfs_reads_nothing() = runTest {
        renderer.supported = false
        val items = items()
        val viewer = viewer(start = 3, items = items)

        assertEquals(ViewerPage.CantShow, viewer.page(3))
        assertEquals(0, items.openCalls)
        assertTrue(renderer.log.isEmpty(), "no buffer either")
        assertTrue(loaded.none { it == id(3).toList() }, "decided as a PDF, not handed to the image route")
    }

    @Test
    fun a_pdf_over_the_cap_reads_nothing() = runTest {
        listing = listOf(entry(3, "huge.pdf", "application/pdf", bytes = PDF_CAP + 1))
        val items = items()
        val viewer = viewer(start = 3, items = items)

        assertEquals(ViewerPage.TooLarge, viewer.page(3))
        assertEquals(0, items.openCalls)
        assertTrue(renderer.log.isEmpty())
    }

    /** Without a renderer the PDF page is what it was before PDFs: its badge, through the image route. */
    @Test
    fun without_a_renderer_a_pdf_shows_its_badge_as_before() = runTest {
        val items = items()
        viewer(start = 3, items = items, pdfs = null)

        assertEquals(0, items.openCalls)
        assertTrue(loaded.any { it == id(3).toList() }, "it took the image route, whose plan shows the badge")
    }

    // ---- leaving, coming back, one at a time, a lock -----------------------------------------------

    @Test
    fun leaving_the_pdf_closes_its_document_and_drops_its_page_back_to_waiting() = runTest {
        val viewer = viewer(start = 3)

        viewer.showPage(1) // the photo
        assertEquals(listOf("buffer $size", "open $size", "close document", "close buffer"), renderer.log)
        assertEquals(ViewerPage.Loading, viewer.page(3), "beside the photo, waiting to be shown again")
        assertTrue(viewer.pdfPages.value.isEmpty())
    }

    @Test
    fun coming_back_reads_it_again_and_resumes_where_it_was_left() = runTest {
        val items = items()
        val viewer = viewer(start = 3, items = items)
        viewer.pdfScrolled(page = 2, offsetPx = 120)

        viewer.showPage(1)
        viewer.showPage(0)

        assertEquals(2, items.openCalls, "read again: nothing of it was kept while it was not shown")
        assertEquals(ViewerPage.Pdf(3, threePages, PdfPlace(2, 120)), viewer.page(3))
    }

    @Test
    fun a_place_is_remembered_per_pdf() = runTest {
        val viewer = viewer(start = 3)
        viewer.pdfScrolled(page = 2, offsetPx = 120)

        viewer.showPage(2) // a.pdf, never scrolled
        assertEquals(ViewerPage.Pdf(3, threePages, PdfPlace()), viewer.page(1))
    }

    /** The PDF left is closed before the next is read: never two whole files in memory at once. */
    @Test
    fun one_pdf_is_open_at_a_time() = runTest {
        val viewer = viewer(start = 3)

        viewer.showPage(2) // straight to a.pdf
        val closedFirst = renderer.log.indexOf("close document")
        val nextRead = renderer.log.lastIndexOf("buffer $size")
        assertTrue(closedFirst in 0 until nextRead, "${renderer.log}")
        assertTrue(viewer.page(1) is ViewerPage.Pdf)
    }

    @Test
    fun a_lock_closes_the_pdf_shown_and_drops_its_pages() = runTest {
        val viewer = viewer(start = 3)

        lifecycle.destroy()
        assertEquals(listOf("close document", "close buffer"), renderer.log.takeLast(2))
        assertTrue(viewer.pdfPages.value.isEmpty())
    }

    /** Opening and closing go through the one PDF context, so a close queues behind a render. */
    @Test
    fun the_document_is_opened_and_closed_on_the_pdf_context() = runTest {
        val pdfThread = ManualDispatcher()
        val viewer = viewer(start = 3, pdfContext = pdfThread)
        assertEquals(listOf("buffer $size"), renderer.log, "read, but not opened until the PDF context runs")

        pdfThread.drain()
        assertTrue(viewer.page(3) is ViewerPage.Pdf)
        viewer.showPage(1)
        assertFalse("close document" in renderer.log, "queued, not done on the caller's thread")
        pdfThread.drain()
        assertTrue("close document" in renderer.log)
    }

    // The hooks below are set once the viewer exists (it opens on the photo), then the PDF is swiped to:
    // a hook firing during construction would throw on the not-yet-assigned viewer and pass by accident.

    @Test
    fun swiping_away_mid_read_releases_the_buffer_unopened() = runTest {
        val items = items()
        val viewer = viewer(start = 2, items = items)
        duringRead = { viewer.showPage(1) }
        viewer.showPage(0) // b.pdf

        assertEquals(1, items.openCalls, "the read did start")
        assertEquals(listOf("buffer $size", "close buffer"), renderer.log)
        assertFalse(viewer.page(3) is ViewerPage.Pdf)
    }

    @Test
    fun a_lock_mid_read_releases_the_buffer_unopened() = runTest {
        val items = items()
        val viewer = viewer(start = 2, items = items)
        duringRead = { lifecycle.destroy() }
        viewer.showPage(0)

        assertEquals(1, items.openCalls, "the read did start")
        assertEquals(listOf("buffer $size", "close buffer"), renderer.log)
    }

    /** Opened just as it was left: the document made for nobody is closed, not kept. */
    @Test
    fun a_document_opened_for_a_page_left_while_it_opened_is_closed() = runTest {
        val viewer = viewer(start = 2)
        renderer.onOpen = { viewer.showPage(1) }
        viewer.showPage(0)

        assertEquals(listOf("buffer $size", "open $size", "close document", "close buffer"), renderer.log)
        assertFalse(viewer.page(3) is ViewerPage.Pdf)
    }

    @Test
    fun a_document_opened_as_the_vault_locked_is_closed() = runTest {
        val viewer = viewer(start = 2)
        renderer.onOpen = { lifecycle.destroy() }
        viewer.showPage(0)

        assertEquals(listOf("buffer $size", "open $size", "close document", "close buffer"), renderer.log)
    }

    /**
     * Away and straight back: the abandoned read's clean-up must clear only its own slot. If it cleared
     * the new read's, the next refresh would start a third read beside it — two whole files at once.
     */
    @Test
    fun an_abandoned_read_does_not_clear_the_next_ones_place() = runTest {
        val io = ManualDispatcher()
        val pdfThread = ManualDispatcher()
        val items = items()
        val viewer = viewer(start = 2, items = items, io = io, pdfContext = pdfThread)
        viewer.showPage(0) // read 1, queued
        viewer.showPage(1) // abandoned
        viewer.showPage(0) // read 2, queued behind it

        io.runNext() // read 1 wakes up cancelled and cleans up, read 2 still waiting
        viewer.viewportMeasured(1_000, 2_000) // any refresh while read 2 waits
        io.drain()
        pdfThread.drain()

        assertEquals(1, items.openCalls, "only read 2 ran: ${renderer.log}")
        assertTrue(viewer.page(3) is ViewerPage.Pdf)
    }

    // ---- drawing pages ---------------------------------------------------------------------------

    private fun ViewerComponent<*>.drawnWidths() = pdfPages.value.mapValues { it.value?.width }

    @Test
    fun the_pages_on_screen_and_one_beyond_are_drawn_at_the_width_shown() = runTest {
        val viewer = viewer(start = 3)

        viewer.pdfViewport(visible = 0..0, shownWidthPx = 1000, zoom = 1f, screenWidthPx = 1000)
        assertEquals(mapOf(0 to 1000, 1 to 1000), viewer.drawnWidths())
    }

    @Test
    fun scrolling_drops_the_pages_left_behind_and_keeps_those_still_near() = runTest {
        val viewer = viewer(start = 3)
        viewer.pdfViewport(0..0, 1000, 1f, 1000)

        viewer.pdfViewport(2..2, 1000, 1f, 1000)
        assertEquals(mapOf(1 to 1000, 2 to 1000), viewer.drawnWidths())
        assertEquals(1, renderer.log.count { it == "render 1@1000" }, "page 1 was kept, not drawn again")
    }

    /**
     * Each page is drawn by its own shape: an A4 page at the width shown, a long receipt beside it
     * narrower, where its pixels run out — and only that one page, not the whole window, pays for it.
     */
    @Test
    fun each_page_is_drawn_within_the_limit_by_its_own_shape() = runTest {
        renderer.pages = listOf(PdfPageSize(595, 842), PdfPageSize(595, 5000), PdfPageSize(595, 842))
        val viewer = viewer(start = 3)

        viewer.pdfViewport(0..1, 1440, 1f, 1440)

        assertEquals(mapOf(0 to 1440, 1 to 1412, 2 to 1440), viewer.drawnWidths())
    }

    @Test
    fun zooming_draws_the_page_again_sharper_up_to_the_cap() = runTest {
        val viewer = viewer(start = 3)
        viewer.pdfViewport(0..0, 1000, 1f, 1000)

        viewer.pdfViewport(0..0, 1000, 5f, 1000)
        assertEquals(mapOf(0 to 2000, 1 to 2000), viewer.drawnWidths())
    }

    @Test
    fun a_page_that_could_not_be_drawn_is_there_as_nothing_and_the_others_still_are() = runTest {
        renderer.unrenderable = setOf(1)
        val viewer = viewer(start = 3)

        viewer.pdfViewport(0..1, 1000, 1f, 1000)
        assertEquals(mapOf(0 to 1000, 1 to null, 2 to 1000), viewer.drawnWidths())
        assertTrue(1 in viewer.pdfPages.value, "present as null, not absent: it was tried")
    }

    @Test
    fun pages_are_drawn_on_the_pdf_context() = runTest {
        val pdfThread = ManualDispatcher()
        val viewer = viewer(start = 3, pdfContext = pdfThread)
        pdfThread.drain()

        viewer.pdfViewport(0..0, 1000, 1f, 1000)
        assertTrue(renderer.log.none { it.startsWith("render") }, "not on the caller's thread")
        pdfThread.drain()
        assertEquals(mapOf(0 to 1000, 1 to 1000), viewer.drawnWidths())
    }

    /** Renders queued for a PDF that was then left are never run, and closing waits behind none of them. */
    @Test
    fun leaving_cancels_the_renders_still_queued() = runTest {
        val pdfThread = ManualDispatcher()
        val viewer = viewer(start = 3, pdfContext = pdfThread)
        pdfThread.drain()
        viewer.pdfViewport(0..0, 1000, 1f, 1000)

        viewer.showPage(1)
        pdfThread.drain()
        assertTrue(renderer.log.none { it.startsWith("render") }, "${renderer.log}")
        assertTrue("close document" in renderer.log)
        assertTrue(viewer.pdfPages.value.isEmpty())
    }

    /** A render under way when the page is left finishes (it cannot be stopped) and its picture is dropped. */
    @Test
    fun a_page_drawn_after_its_pdf_was_left_is_dropped() = runTest {
        val viewer = viewer(start = 3)
        renderer.onRender = { viewer.showPage(1) }

        viewer.pdfViewport(0..0, 1000, 1f, 1000)
        assertTrue(viewer.pdfPages.value.isEmpty(), "${viewer.pdfPages.value}")
        assertEquals("close document", renderer.log.dropWhile { !it.startsWith("render") }[1], "closed after that render")
    }

    @Test
    fun a_viewport_with_no_pdf_shown_draws_nothing() = runTest {
        val viewer = viewer(start = 2) // the photo
        viewer.pdfViewport(0..0, 1000, 1f, 1000)
        assertTrue(viewer.pdfPages.value.isEmpty())
        assertTrue(renderer.log.isEmpty())
    }

    /** One document at a time: the PDF beside the one shown is not read until it is the one shown. */
    @Test
    fun a_pdf_beside_the_one_shown_is_not_read() = runTest {
        val items = items()
        val viewer = viewer(start = 3, items = items)

        viewer.showPage(1) // the photo; b.pdf is beside it, a.pdf too
        assertEquals(1, items.openCalls, "only b.pdf, when it was shown")
        assertFalse(viewer.page(1) is ViewerPage.Pdf, "a.pdf beside the photo is not open")
    }
}
