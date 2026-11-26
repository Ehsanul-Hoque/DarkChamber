package dev.ehsanfahad.darkchamber.feature.vault

import dev.ehsanfahad.darkchamber.feature.vault.api.PdfOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PDF fake keeps the seam's contract, so the viewer's tests built on it prove something about the
 * viewer — a fake that is laxer than the platform would let a viewer bug pass.
 */
class PdfFakesTest {
    private fun filledBuffer(
        renderer: FakePdfRenderer,
        bytes: Int = 10,
    ) = renderer.newBuffer(bytes.toLong()).also { buffer ->
        buffer.sink.write(Buffer().apply { write(ByteArray(bytes)) }, bytes.toLong())
    }

    @Test
    fun a_refusal_has_already_released_its_buffer() {
        val renderer = FakePdfRenderer(refusal = PdfRefusal.NeedsPassword)
        val opened = filledBuffer(renderer).open()

        assertEquals(PdfOpened.Refused(PdfRefusal.NeedsPassword), opened)
        assertTrue(renderer.buffers.single().closed)
        assertEquals(listOf("buffer 10", "open 10", "close buffer"), renderer.log)
    }

    @Test
    fun closing_the_document_releases_its_buffer_once() {
        val renderer = FakePdfRenderer()
        val document = assertIs<PdfOpened.Document>(filledBuffer(renderer).open()).document

        document.close()
        document.close()

        assertTrue(renderer.buffers.single().closed)
        assertEquals(listOf("buffer 10", "open 10", "close document", "close buffer"), renderer.log)
    }

    @Test
    fun a_closed_document_refuses_to_render_and_an_unrenderable_page_is_null() {
        val renderer = FakePdfRenderer(unrenderable = setOf(1))
        val document = assertIs<PdfOpened.Document>(filledBuffer(renderer).open()).document

        assertEquals(1440, document.render(0, 1440)?.width)
        assertNull(document.render(1, 1440))
        document.close()
        assertFailsWith<IllegalStateException> { document.render(0, 1440) }
    }
}
