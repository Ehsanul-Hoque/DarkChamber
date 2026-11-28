package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import dev.ehsanfahad.darkchamber.feature.vault.api.FormatNote
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.TextDocument
import dev.ehsanfahad.darkchamber.feature.vault.api.TextKind
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The viewer showing text files over a fake vault, every dispatcher injected. The listing, newest
 * first: 4 (a.txt), 3 (b.md, stored as an unknown type), 2 (photo), 1 (c.json) — so a.txt is page 0,
 * b.md page 1, the photo page 2 and c.json page 3.
 */
class ViewerTextTest {
    private val lifecycle = LifecycleRegistry().apply { resume() }

    private fun id(n: Int) = byteArrayOf(n.toByte(), 0x7A)

    private fun key(n: Int) = id(n).toListKey()

    /** Each item's plaintext, as the vault would write it; an item's stored size is its length unless set. */
    private val contents =
        mutableMapOf(
            4 to "first line\nsecond line\n".encodeToByteArray(),
            3 to "# Title\n\nSome *text*.\n".encodeToByteArray(),
            2 to ByteArray(10),
            1 to "{\"a\":[1,2]}".encodeToByteArray(),
        )

    private val storedSize = mutableMapOf<Int, Long>()

    private fun entry(
        n: Int,
        name: String,
        mime: String,
    ): VaultItemSummary =
        summary(
            id(n),
            readableMeta(name, mime),
            size = storedSize[n] ?: contents.getValue(n).size.toLong(),
            createdAt =
                n * 10L,
        )

    private fun listing() =
        listOf(
            entry(1, "c.json", "application/json"),
            entry(2, "photo.jpg", "image/jpeg"),
            entry(3, "b.md", "application/octet-stream"),
            entry(4, "a.txt", "text/plain"),
        )

    /** Runs inside the vault read, after half the file is written — where a swipe or a lock can land mid-read. */
    private var duringRead: () -> Unit = {}

    /** What the vault read answers once it has written the whole file. */
    private var readAnswer: Outcome<Unit> = Outcome.Ok(Unit)

    /** Every buffer the viewer read a text file into, so a test can see it was zeroed. */
    private val buffers = mutableListOf<ByteArray>()

    /** The items whose second write the sink took — a decrypt that was not stopped partway. */
    private val wroteAll = mutableListOf<Int>()

    private fun ByteArray.zeroed() = all { it == 0.toByte() }

    private fun items() =
        FakeVaultItems(
            onOpen = { id: ByteArray, sink: RawSink ->
                val bytes = contents.getValue(id[0].toInt())
                val half = bytes.size / 2
                sink.write(Buffer().apply { write(bytes, 0, half) }, half.toLong())
                duringRead()
                sink.write(Buffer().apply { write(bytes, half, bytes.size) }, (bytes.size - half).toLong())
                wroteAll += id[0].toInt()
                readAnswer
            },
        ).apply { summaries.value = listing() }

    /** How the viewer reads Markdown: the real reader unless a test makes it fail. */
    private var readMarkdown: (
        String,
        () -> Boolean,
    ) -> MarkdownDocument? = { text, cancelled -> MarkdownModel.from(text, cancelled = cancelled) }

    private fun TestScope.viewer(
        start: Int,
        items: FakeVaultItems = items(),
        io: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
    ) = ViewerComponent(
        componentContext = DefaultComponentContext(lifecycle),
        observeGallery = ObserveGallery(items),
        observeAlbum = ObserveAlbum(items),
        load = { _: GalleryItem, _: Int -> LoadedPage.Page(ViewerPage.CantShow) },
        shred = { Outcome.Ok(Unit) },
        filter = ItemFilter.All,
        controls = GalleryControls(),
        startId = id(start),
        onBack = {},
        openRange = items::openRange,
        playerFactory = null,
        mainContext = UnconfinedTestDispatcher(testScheduler),
        ioContext = io,
        releaseContext = UnconfinedTestDispatcher(testScheduler),
        openObject = items::open,
        textBuffer = { size -> ByteArray(size).also { buffers += it } },
        readMarkdown = { text, cancelled -> readMarkdown(text, cancelled) },
    ).also { it.viewportMeasured(1_000, 2_000) }

    private fun ViewerComponent<*>.page(n: Int) = state.value.pages[key(n)]

    private fun ViewerComponent<*>.text(n: Int): TextDocument? = texts.value[key(n)]

    private fun TextDocument.lines() = List(plain.lineCount) { plain.line(it) }

    // ---- reading ---------------------------------------------------------------------------------

    @Test
    fun a_text_file_shown_is_read_whole_and_shown_by_its_kind() =
        runTest {
            val items = items()
            val viewer = viewer(start = 4, items = items)

            assertEquals(1, items.openCalls)
            assertEquals(ViewerPage.Text(TextKind.Plain), viewer.page(4))
            assertEquals(listOf("first line", "second line"), viewer.text(4)!!.lines())
            assertEquals(setOf(key(4)), viewer.texts.value.keys, "only the page shown")
        }

    /** Text is read for the page shown only: a neighbour waits, unread, until it is swiped to. */
    @Test
    fun a_neighbouring_text_file_is_not_read_until_shown() =
        runTest {
            val items = items()
            val viewer = viewer(start = 2, items = items) // the photo, between b.md and c.json

            assertEquals(0, items.openCalls)
            assertEquals(ViewerPage.Loading, viewer.page(3))
            assertEquals(ViewerPage.Loading, viewer.page(1))
            assertTrue(viewer.texts.value.isEmpty())
        }

    /** The kind comes from the stored details: an `.md` stored as an unknown type is Markdown. */
    @Test
    fun each_kind_is_read_as_its_kind() =
        runTest {
            val viewer = viewer(start = 3)
            assertEquals(ViewerPage.Text(TextKind.Markdown), viewer.page(3))
            viewer.showPage(3)
            assertEquals(ViewerPage.Text(TextKind.Json), viewer.page(1))
        }

    @Test
    fun a_read_the_vault_refused_shows_none_of_it_and_says_why() =
        runTest {
            readAnswer = Outcome.Err(VaultError.Crypto.IntegrityFailure)
            val viewer = viewer(start = 4)
            assertEquals(ViewerPage.Unverified, viewer.page(4))
            assertTrue(viewer.texts.value.isEmpty(), "nothing of a file that failed its check")
        }

    @Test
    fun a_newer_format_asks_for_an_update_and_anything_else_names_no_cause() =
        runTest {
            readAnswer = Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion)
            assertEquals(ViewerPage.NeedsUpdate, viewer(start = 4).page(4))
            readAnswer = Outcome.Err(VaultError.Io(IllegalStateException("x")))
            assertEquals(ViewerPage.CouldntOpen, viewer(start = 3).page(3))
        }

    @Test
    fun a_text_file_over_the_cap_reads_nothing() =
        runTest {
            storedSize[4] = TEXT_CAP + 1
            val items = items()
            val viewer = viewer(start = 4, items = items)
            assertEquals(ViewerPage.TooLarge, viewer.page(4))
            assertEquals(0, items.openCalls)
        }

    @Test
    fun a_negative_stored_size_reads_nothing() =
        runTest {
            storedSize[4] = -1
            val items = items()
            val viewer = viewer(start = 4, items = items)
            assertEquals(ViewerPage.CouldntOpen, viewer.page(4))
            assertEquals(0, items.openCalls)
        }

    /** The file is read into exactly its stored size: more or fewer bytes than that is not the file it says. */
    @Test
    fun a_file_not_its_stored_size_couldnt_open() =
        runTest {
            storedSize[4] = contents.getValue(4).size - 1L
            assertEquals(ViewerPage.CouldntOpen, viewer(start = 4).page(4), "longer than stored")
            storedSize[4] = contents.getValue(4).size + 1L
            assertEquals(ViewerPage.CouldntOpen, viewer(start = 4).page(4), "shorter than stored")
        }

    @Test
    fun an_empty_text_file_is_shown_with_no_lines() =
        runTest {
            contents[4] = ByteArray(0)
            val viewer = viewer(start = 4)
            assertEquals(ViewerPage.Text(TextKind.Plain), viewer.page(4))
            assertEquals(0, viewer.text(4)!!.plain.lineCount)
        }

    /** More lines than a page holds is too large to show, whatever the bytes come to; the limit itself is shown. */
    @Test
    fun a_text_file_of_more_lines_than_a_page_holds_is_too_large() =
        runTest {
            contents[4] = ByteArray(TEXT_MAX_LINES + 1) { 0x0A }
            val over = viewer(start = 4)
            assertEquals(ViewerPage.TooLarge, over.page(4))
            assertTrue(over.texts.value.isEmpty())
            assertTrue(buffers.single().zeroed())

            contents[4] = ByteArray(TEXT_MAX_LINES) { 0x0A }
            val at = viewer(start = 4)
            assertEquals(ViewerPage.Text(TextKind.Plain), at.page(4))
            assertEquals(TEXT_MAX_LINES, at.text(4)!!.plain.lineCount)
        }

    @Test
    fun bytes_that_are_not_text_are_said_to_be_so() =
        runTest {
            contents[4] = byteArrayOf(0x61, 0x00, 0x62)
            val viewer = viewer(start = 4)
            assertEquals(ViewerPage.NotText, viewer.page(4))
            assertTrue(viewer.texts.value.isEmpty())
        }

    /** Reading and decoding happen on the background context: nothing is decoded until it runs. */
    @Test
    fun reading_and_decoding_run_on_the_background_context() =
        runTest {
            val io = ManualDispatcher()
            val viewer = viewer(start = 4, io = io)
            assertEquals(ViewerPage.Loading, viewer.page(4))
            assertTrue(viewer.texts.value.isEmpty())

            io.drain()
            assertEquals(ViewerPage.Text(TextKind.Plain), viewer.page(4))
        }

    // ---- the bytes are zeroed, whatever happens ----------------------------------------------------

    @Test
    fun the_buffer_is_zeroed_once_the_text_is_decoded() =
        runTest {
            viewer(start = 4)
            assertTrue(buffers.single().zeroed())
            assertEquals(contents.getValue(4).size, buffers.single().size, "sized from the stored size")
        }

    @Test
    fun the_buffer_is_zeroed_when_the_vault_refused_the_read() =
        runTest {
            readAnswer = Outcome.Err(VaultError.Crypto.IntegrityFailure)
            viewer(start = 4)
            assertTrue(buffers.single().zeroed())
        }

    @Test
    fun the_buffer_is_zeroed_when_the_bytes_are_not_text() =
        runTest {
            contents[4] = byteArrayOf(0x61, 0x00, 0x62)
            viewer(start = 4)
            assertTrue(buffers.single().zeroed())
        }

    @Test
    fun the_buffer_is_zeroed_when_the_file_is_longer_than_stored() =
        runTest {
            storedSize[4] = contents.getValue(4).size - 1L
            viewer(start = 4)
            assertTrue(buffers.single().zeroed())
        }

    // ---- leaving, coming back, a lock --------------------------------------------------------------

    // The hooks below are set once the viewer exists (it opens on the photo), then a text file is swiped
    // to: a hook firing during construction would throw on the not-yet-assigned viewer and pass by accident.

    @Test
    fun swiping_away_mid_read_stops_the_decrypt_and_shows_nothing() =
        runTest {
            val items = items()
            val viewer = viewer(start = 2, items = items)
            duringRead = { viewer.showPage(2) } // back to the photo
            viewer.showPage(1) // b.md

            assertEquals(1, items.openCalls, "the read did start")
            assertTrue(3 !in wroteAll, "the decrypt stopped at the next write")
            assertTrue(viewer.texts.value.isEmpty())
            assertEquals(ViewerPage.Loading, viewer.page(3), "beside the photo, waiting to be read when shown")
            assertTrue(buffers.single().zeroed())
        }

    @Test
    fun a_lock_mid_read_stops_the_decrypt_and_shows_nothing() =
        runTest {
            val viewer = viewer(start = 2)
            duringRead = { lifecycle.destroy() }
            viewer.showPage(1) // b.md

            assertTrue(3 !in wroteAll, "the decrypt stopped at the next write")
            assertTrue(viewer.texts.value.isEmpty())
            assertTrue(buffers.single().zeroed())
        }

    /**
     * Back mid-read: the viewer stops the decrypt and lets the text go itself, before the host removes it
     * — closing is not a lock, and the host may keep the viewer a moment longer for its animation.
     */
    @Test
    fun closing_the_viewer_mid_read_stops_the_decrypt_and_shows_nothing() =
        runTest {
            val items = items()
            val viewer = viewer(start = 2, items = items)
            duringRead = { viewer.back() }
            viewer.showPage(1) // b.md

            assertEquals(1, items.openCalls, "the read did start")
            assertTrue(3 !in wroteAll, "the decrypt stopped at the next write")
            assertTrue(viewer.texts.value.isEmpty())
            assertTrue(buffers.single().zeroed())
        }

    @Test
    fun closing_the_viewer_lets_the_text_shown_go() =
        runTest {
            val viewer = viewer(start = 4)
            assertTrue(viewer.texts.value.isNotEmpty())
            viewer.back()
            assertTrue(viewer.texts.value.isEmpty())
        }

    @Test
    fun leaving_a_text_file_drops_its_text_and_coming_back_reads_it_again() =
        runTest {
            val items = items()
            val viewer = viewer(start = 4, items = items)

            viewer.showPage(1) // b.md, a.txt beside it
            assertEquals(setOf(key(3)), viewer.texts.value.keys, "a.txt's text let go")
            assertEquals(ViewerPage.Loading, viewer.page(4), "waiting to be read again")

            viewer.showPage(0)
            assertEquals(3, items.openCalls, "a.txt, b.md, then a.txt again: nothing kept while not shown")
            assertEquals(setOf(key(4)), viewer.texts.value.keys)
        }

    @Test
    fun a_lock_drops_the_text_shown() =
        runTest {
            val viewer = viewer(start = 4)
            lifecycle.destroy()
            assertTrue(viewer.texts.value.isEmpty())
        }

    // ---- formatted views -----------------------------------------------------------------------------

    @Test
    fun a_markdown_file_has_its_formatted_view_beside_its_lines() =
        runTest {
            val viewer = viewer(start = 3)
            assertEquals(ViewerPage.Text(TextKind.Markdown), viewer.page(3))
            val document = viewer.text(3)!!
            assertEquals(listOf("# Title", "", "Some *text*."), document.lines(), "as stored")
            val blocks = assertIs<FormattedText.Markdown>(document.formatted).document.blocks
            assertEquals(
                listOf(
                    MdBlock.Heading(1, listOf(MdSpan("Title")), "title"),
                    MdBlock.Paragraph(listOf(MdSpan("Some "), MdSpan("text", emphasis = true), MdSpan("."))),
                ),
                blocks,
            )
        }

    /** Markdown is formatted up to 1 MiB; one byte more is shown as written, and says so. */
    @Test
    fun markdown_over_a_mebibyte_is_shown_as_written_with_a_note() =
        runTest {
            assertEquals(1L * 1024 * 1024, MARKDOWN_FORMAT_CAP)
            contents[3] = "a".repeat(MARKDOWN_FORMAT_CAP.toInt()).encodeToByteArray()
            val atCap = viewer(start = 3)
            assertEquals(ViewerPage.Text(TextKind.Markdown), atCap.page(3))
            assertIs<FormattedText.Markdown>(atCap.text(3)!!.formatted)

            contents[3] = "a".repeat(MARKDOWN_FORMAT_CAP.toInt() + 1).encodeToByteArray()
            val over = viewer(start = 3)
            assertEquals(ViewerPage.Text(TextKind.Markdown, FormatNote.TooLarge), over.page(3))
            assertNull(over.text(3)!!.formatted)
            assertEquals(1, over.text(3)!!.plain.lineCount, "its lines are there")
        }

    @Test
    fun markdown_the_reader_cannot_format_is_shown_as_written_with_a_note() =
        runTest {
            contents[3] = ("> ".repeat(MARKDOWN_MAX_NESTING + 1) + "x\n").encodeToByteArray()
            val viewer = viewer(start = 3)
            assertEquals(ViewerPage.Text(TextKind.Markdown, FormatNote.TooComplex), viewer.page(3))
            assertNull(viewer.text(3)!!.formatted)
        }

    /** Styles nested thousands deep: the reader's own limit, all the way to the page — never a crash. */
    @Test
    fun markdown_nested_thousands_deep_is_shown_as_written_with_a_note() =
        runTest {
            contents[3] = ("**a ".repeat(20_000) + "b" + " a**".repeat(20_000)).encodeToByteArray()
            val viewer = viewer(start = 3)
            assertEquals(ViewerPage.Text(TextKind.Markdown, FormatNote.TooComplex), viewer.page(3))
            assertEquals(1, viewer.text(3)!!.plain.lineCount, "its lines are there")
        }

    /**
     * Formatting a file is the one step that reads what a file holds in depth. Should it fail in a way
     * nothing foresaw, the file is shown as written with a note — not an error that ends the app.
     */
    @Test
    fun a_formatting_failure_shows_the_file_as_written_with_a_note() =
        runTest {
            readMarkdown = { _, _ -> throw IllegalStateException("unforeseen") }
            val viewer = viewer(start = 3)
            assertEquals(ViewerPage.Text(TextKind.Markdown, FormatNote.TooComplex), viewer.page(3))
            assertNull(viewer.text(3)!!.formatted)
            assertEquals("# Title", viewer.text(3)!!.plain.line(0))
            assertTrue(buffers.single().zeroed())
        }

    /** The reader is told when the read it serves has been left behind, so it can stop where it is. */
    @Test
    fun formatting_is_told_when_the_page_is_swiped_away() =
        runTest {
            val seen = mutableListOf<Boolean>()
            val viewer = viewer(start = 2) // the photo
            readMarkdown = { _, cancelled ->
                seen += cancelled()
                viewer.showPage(2) // back to the photo, mid-format
                seen += cancelled()
                null
            }
            viewer.showPage(1) // b.md

            assertEquals(listOf(false, true), seen)
            assertTrue(viewer.texts.value.isEmpty())
        }

    /** A cancellation is not a failure to format: it stops the read, and nothing is shown for it. */
    @Test
    fun a_cancelled_format_shows_nothing_rather_than_a_note() =
        runTest {
            readMarkdown = { _, _ -> throw kotlin.coroutines.cancellation.CancellationException("stopped") }
            val viewer = viewer(start = 3)
            assertEquals(ViewerPage.Loading, viewer.page(3))
            assertNull(viewer.text(3))
        }

    @Test
    fun a_json_file_has_its_indented_view_beside_its_lines() =
        runTest {
            val viewer = viewer(start = 1)
            assertEquals(ViewerPage.Text(TextKind.Json), viewer.page(1))
            val formatted = assertIs<FormattedText.Json>(viewer.text(1)!!.formatted)
            assertEquals(
                listOf("{", "  \"a\": [", "    1,", "    2", "  ]", "}"),
                List(formatted.lines.lineCount) { formatted.lines.line(it) },
            )
        }

    @Test
    fun json_that_is_not_valid_is_shown_as_written_with_a_note() =
        runTest {
            contents[1] = "{\"a\":".encodeToByteArray()
            val viewer = viewer(start = 1)
            assertEquals(ViewerPage.Text(TextKind.Json, FormatNote.NotJson), viewer.page(1))
            assertNull(viewer.text(1)!!.formatted)
        }

    @Test
    fun json_too_large_to_indent_is_shown_as_written_with_a_note() =
        runTest {
            contents[1] = ("[".repeat(20_000) + "1" + "]".repeat(20_000)).encodeToByteArray()
            val viewer = viewer(start = 1)
            assertEquals(ViewerPage.Text(TextKind.Json, FormatNote.TooLarge), viewer.page(1))
            assertNull(viewer.text(1)!!.formatted)
        }

    /** `[1,1,…,1]` of [elements]: indented, one line per element plus the two brackets. */
    private fun ones(elements: Int) = ("[" + "1,".repeat(elements - 1) + "1]").encodeToByteArray()

    /** Indenting adds lines: past what a page holds, the file is shown as written, with a note. */
    @Test
    fun json_whose_indented_lines_pass_what_a_page_holds_is_shown_as_written_with_a_note() =
        runTest {
            contents[1] = ones(TEXT_MAX_LINES - 1) // indents to one line over
            val over = viewer(start = 1)
            assertEquals(ViewerPage.Text(TextKind.Json, FormatNote.TooLarge), over.page(1))
            assertNull(over.text(1)!!.formatted)
            assertEquals(1, over.text(1)!!.plain.lineCount)

            contents[1] = ones(TEXT_MAX_LINES - 2) // indents to exactly the limit
            val at = viewer(start = 1)
            assertEquals(ViewerPage.Text(TextKind.Json), at.page(1))
            assertEquals(TEXT_MAX_LINES, assertIs<FormattedText.Json>(at.text(1)!!.formatted).lines.lineCount)
        }

    @Test
    fun plain_text_has_no_formatted_view_and_no_note() =
        runTest {
            val viewer = viewer(start = 4)
            assertEquals(ViewerPage.Text(TextKind.Plain), viewer.page(4))
            assertNull(viewer.text(4)!!.formatted)
        }

    /** One choice for every formatted file, kept from file to file; a new viewer starts formatted. */
    @Test
    fun the_formatted_choice_lasts_for_the_viewers_life() =
        runTest {
            val viewer = viewer(start = 3)
            assertTrue(viewer.state.value.formatted, "formatted at first")

            viewer.toggleFormatted()
            assertFalse(viewer.state.value.formatted)
            viewer.showPage(3) // c.json
            assertFalse(viewer.state.value.formatted, "kept for the next file")
            viewer.toggleFormatted()
            assertTrue(viewer.state.value.formatted, "and switched back")
            viewer.toggleFormatted()

            lifecycle.destroy()
            assertTrue(viewer(start = 3).state.value.formatted, "a new viewer, after a lock, starts formatted")
        }

    /** A refused page stays refused while it is in the window; it is not read again on each swipe. */
    @Test
    fun a_refused_text_page_is_not_read_again_while_in_view() =
        runTest {
            contents[4] = byteArrayOf(0x00)
            val items = items()
            val viewer = viewer(start = 4, items = items)
            viewer.showPage(1)
            viewer.showPage(0)
            assertEquals(ViewerPage.NotText, viewer.page(4))
            assertEquals(2, items.openCalls, "a.txt once, b.md once")
        }
}
