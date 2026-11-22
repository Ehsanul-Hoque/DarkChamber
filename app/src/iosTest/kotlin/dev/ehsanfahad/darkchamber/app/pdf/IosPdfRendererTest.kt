package dev.ehsanfahad.darkchamber.app.pdf

import androidx.compose.ui.graphics.ImageBitmap
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPageSize
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.Buffer
import kotlinx.io.IOException

/**
 * The iOS renderer over the CoreGraphics-made PDFs the Android test reads (`PdfFixtures`): three
 * full-bleed pages — red and green portrait A4, blue landscape A4 — the same with a password, and the
 * plain one cut in half. CoreGraphics runs in the simulator, so this is the real engine; only an
 * iPhone's memory behaviour is left unverified.
 */
class IosPdfRendererTest {
    private val renderer = IosPdfRenderer()

    /** Fills a buffer the way the vault does — in pieces, through its sink. */
    private fun filled(bytes: ByteArray): VaultPdfBuffer {
        val buffer = renderer.newBuffer(bytes.size.toLong())
        bytes.toList().chunked(1_000).forEach { piece ->
            buffer.sink.write(Buffer().apply { write(piece.toByteArray()) }, piece.size.toLong())
        }
        return buffer
    }

    /** The page's colour by which channel carries it (calibrated colours do not arrive pure). */
    private fun assertDominant(channel: String, picture: ImageBitmap, x: Int, y: Int) {
        val pixel = IntArray(1)
        picture.readPixels(pixel, startX = x, startY = y, width = 1, height = 1)
        val argb = pixel[0]
        val rgb = mapOf("red" to (argb shr 16 and 0xFF), "green" to (argb shr 8 and 0xFF), "blue" to (argb and 0xFF))
        val others = rgb.filterKeys { it != channel }.values
        assertTrue(rgb.getValue(channel) > 200 && others.all { it < 100 }, "$channel page drew ${argb.toUInt().toString(16)}")
    }

    @Test
    fun pdfs_are_shown_on_ios() {
        assertTrue(renderer.supported)
    }

    @Test
    fun a_plain_pdf_opens_with_its_pages_and_sizes() {
        val document = assertIs<PdfOpened.Document>(filled(PdfFixtures.plain).open()).document

        assertEquals(3, document.pageCount)
        assertEquals(
            listOf(PdfPageSize(595, 842), PdfPageSize(595, 842), PdfPageSize(842, 595)),
            List(3) { document.pageSize(it) },
        )
        document.close()
    }

    @Test
    fun pages_are_drawn_upright_at_the_width_asked_in_their_own_shape_and_colour() {
        val document = assertIs<PdfOpened.Document>(filled(PdfFixtures.plain).open()).document

        val first = document.render(0, 400)!!
        assertEquals(400, first.width)
        assertEquals(566, first.height) // 400 × 842 / 595
        assertDominant("red", first, 200, 283)
        assertDominant("green", document.render(1, 400)!!, 200, 283)
        val landscape = document.render(2, 400)!!
        assertEquals(282, landscape.height) // 400 × 595 / 842
        assertDominant("blue", landscape, 200, 141)
        document.close()
    }

    /**
     * Red over blue: drawn upside down the top would be blue; drawn without scaling to the width, the
     * 300 px picture would hold only the page's bottom-left corner — blue at the top too.
     */
    @Test
    fun a_page_is_drawn_upright_and_whole() {
        val document = assertIs<PdfOpened.Document>(filled(PdfFixtures.halves).open()).document

        val page = document.render(0, 300)!!
        assertEquals(400, page.height) // 300 × 800 / 600
        assertDominant("red", page, 150, 20)
        assertDominant("blue", page, 150, 380)
        document.close()
    }

    /**
     * A 600×800 page, red over blue, with `/Rotate 90`: shown 800×600, turned clockwise, so the page's
     * top — red — runs down the right-hand side. Drawn unturned it would be portrait, red at the top.
     */
    @Test
    fun a_page_turned_a_quarter_is_shown_turned() {
        val document = assertIs<PdfOpened.Document>(filled(PdfFixtures.turned).open()).document

        assertEquals(PdfPageSize(800, 600), document.pageSize(0))
        val page = document.render(0, 400)!!
        assertEquals(300, page.height) // 400 × 600 / 800
        assertDominant("red", page, 380, 150)
        assertDominant("blue", page, 20, 150)
        document.close()
    }

    /**
     * A 600×800 page painted red with a green 400×600 rectangle, cropped to that rectangle: shown 400×600
     * and green to every edge. Drawn by its media box it would be the wrong shape with red bleed around.
     */
    @Test
    fun a_cropped_page_is_shown_as_its_crop_box() {
        val document = assertIs<PdfOpened.Document>(filled(PdfFixtures.turned).open()).document

        assertEquals(PdfPageSize(400, 600), document.pageSize(1))
        val page = document.render(1, 200)!!
        assertEquals(300, page.height) // 200 × 600 / 400
        for ((x, y) in listOf(3 to 3, 196 to 3, 3 to 296, 196 to 296, 100 to 150)) assertDominant("green", page, x, y)
        document.close()
    }

    /**
     * Only the buffer's last home is wiped on close, so it must never have had another: a buffer made
     * at the item's size takes the whole file — 4 MiB here, in the vault's pieces — without moving. A
     * buffer that grew as it filled would have left earlier, unwiped copies in freed memory.
     */
    @Test
    fun a_buffer_filled_to_its_size_never_moves() {
        val size = 4 * 1024 * 1024
        val buffer = renderer.newBuffer(size.toLong()) as IosPdfBuffer
        val homes = mutableSetOf<Long?>()
        val piece = ByteArray(64 * 1024) { 7 }
        repeat(size / piece.size) {
            buffer.sink.write(Buffer().apply { write(piece) }, piece.size.toLong())
            homes += buffer.address
        }

        assertEquals(size.toLong(), buffer.held)
        assertEquals(1, homes.size, "the bytes moved ${homes.size - 1} times while filling")
        buffer.close()
    }

    @Test
    fun a_closed_document_draws_nothing_and_closes_twice() {
        val document = assertIs<PdfOpened.Document>(filled(PdfFixtures.plain).open()).document
        document.close()
        document.close()
        assertNull(document.render(0, 400))
    }

    @Test
    fun a_password_protected_pdf_is_refused_as_needing_a_password() {
        assertEquals(PdfOpened.Refused(PdfRefusal.NeedsPassword), filled(PdfFixtures.locked).open())
    }

    @Test
    fun a_truncated_pdf_is_refused_as_unreadable() {
        assertEquals(PdfOpened.Refused(PdfRefusal.Unreadable), filled(PdfFixtures.truncated).open())
    }

    /** Our own copy of the plaintext is emptied once the buffer is closed or refused. */
    @Test
    fun the_buffer_holds_the_file_while_filled_and_nothing_once_released() {
        val buffer = filled(PdfFixtures.plain) as IosPdfBuffer
        assertEquals(PdfFixtures.plain.size.toLong(), buffer.held)
        buffer.close()
        assertEquals(0L, buffer.held)
        assertFailsWith<IOException>("a closed buffer takes nothing more") {
            buffer.sink.write(Buffer().apply { write(ByteArray(10)) }, 10)
        }
        assertEquals(0L, buffer.held)

        val refused = filled(PdfFixtures.locked) as IosPdfBuffer
        refused.open()
        assertEquals(0L, refused.held, "a refusal released it")
    }

    @Test
    fun closing_the_document_empties_its_buffer() {
        val buffer = filled(PdfFixtures.plain) as IosPdfBuffer
        val document = assertIs<PdfOpened.Document>(buffer.open()).document
        assertTrue(buffer.held > 0, "the document reads from it while open")
        document.close()
        assertEquals(0L, buffer.held)
    }
}
