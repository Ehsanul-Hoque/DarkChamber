package dev.ehsanfahad.darkchamber

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.system.Os
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ehsanfahad.darkchamber.app.di.platformModule
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPageSize
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfBuffer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfRenderer
import kotlinx.io.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import java.io.File

/**
 * The PDF renderer against the platform's own `PdfRenderer`, which only a device has. The PDFs are
 * written by Apple's CoreGraphics (`assets/pdf/`, see `fixtures/make-pdf-fixtures.swift`), never by
 * this app: three full-bleed pages — red and green portrait A4, blue landscape A4 — the same with a
 * password, the plain one cut in half, and one page red over blue.
 *
 * Every case also reads the process's own descriptor table: the decrypted file must live in a memory
 * file that no path names, and nothing of it may be left open once the document is closed or refused.
 */
@RunWith(AndroidJUnit4::class)
class PdfRendererInstrumentedTest {
    private val koin =
        koinApplication {
            androidContext(ApplicationProvider.getApplicationContext<Context>())
            modules(platformModule())
        }

    private val renderer = koin.koin.get<VaultPdfRenderer>()

    @After
    fun tearDown() {
        koin.close()
    }

    private fun fixture(name: String): ByteArray =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("pdf/$name")
            .use { it.readBytes() }

    /** Fills a buffer the way the vault does — in pieces, through its sink. */
    private fun filled(name: String): VaultPdfBuffer {
        val bytes = fixture(name)
        val buffer = renderer.newBuffer(bytes.size.toLong())
        bytes.toList().chunked(1_000).forEach { piece ->
            buffer.sink.write(Buffer().apply { write(piece.toByteArray()) }, piece.size.toLong())
        }
        return buffer
    }

    /** What each of this process's open descriptors points at. */
    private fun openTargets(): List<String> =
        File("/proc/self/fd").listFiles().orEmpty().mapNotNull { runCatching { Os.readlink(it.path) }.getOrNull() }

    private fun memoryFilesOpen(): Int = openTargets().count { it.contains("memfd:viewer-pdf") }

    /**
     * The page's colour, by which channel carries it: CoreGraphics writes the fixtures' colours in a
     * calibrated space and the renderer converts them, so pure red arrives as `#FF2600` — exact values
     * are the colour pipeline's, not ours. White (an undrawn page) or black would fail every channel.
     */
    private fun assertDominant(
        channel: String,
        pixel: Int,
    ) {
        val rgb = mapOf("red" to Color.red(pixel), "green" to Color.green(pixel), "blue" to Color.blue(pixel))
        val strong = rgb.getValue(channel)
        val others = rgb.filterKeys { it != channel }.values
        assertTrue("$channel page drew ${Integer.toHexString(pixel)}", strong > 200 && others.all { it < 100 })
    }

    private fun assumeAndroid11() = assumeTrue("PDFs are shown from Android 11", Build.VERSION.SDK_INT >= 30)

    @Test
    fun pdfs_are_supported_from_android_11_exactly() {
        assertEquals(Build.VERSION.SDK_INT >= 30, renderer.supported)
    }

    @Test
    fun the_buffer_is_a_memory_file_that_no_path_names() {
        assumeAndroid11()
        val before = memoryFilesOpen()
        val buffer = filled("plain.pdf")

        val ours = openTargets().filter { it.contains("memfd:viewer-pdf") }
        assertEquals("one memory file while it is filled", before + 1, ours.size)
        assertTrue("an anonymous memory file, never a path: $ours", ours.all { it.startsWith("/memfd:") })
        buffer.close()
        assertEquals("released", before, memoryFilesOpen())
    }

    @Test
    fun a_plain_pdf_opens_with_its_pages_sizes_and_colours() {
        assumeAndroid11()
        val before = memoryFilesOpen()
        val opened = filled("plain.pdf").open()
        assertTrue("$opened", opened is PdfOpened.Document)
        val document = (opened as PdfOpened.Document).document

        assertEquals(3, document.pageCount)
        assertEquals(
            listOf(PdfPageSize(595, 842), PdfPageSize(595, 842), PdfPageSize(842, 595)),
            List(3) { document.pageSize(it) },
        )
        val first = document.render(0, 400)!!.asAndroidBitmap()
        assertEquals(400, first.width)
        assertEquals(566, first.height) // 400 × 842 / 595, its own shape
        assertDominant("red", first.getPixel(200, 283))
        assertDominant("green", document.render(1, 400)!!.asAndroidBitmap().getPixel(200, 283))
        val landscape = document.render(2, 400)!!.asAndroidBitmap()
        assertEquals(282, landscape.height) // 400 × 595 / 842
        assertDominant("blue", landscape.getPixel(200, 141))

        document.close()
        assertEquals("closing the document releases its memory file", before, memoryFilesOpen())
        document.close() // twice is fine
        assertNull("nothing drawn after close", document.render(0, 400))
    }

    /** Red over blue: a page drawn upside down, or not scaled to the width, shows blue at the top. */
    @Test
    fun a_page_is_drawn_upright_and_whole() {
        assumeAndroid11()
        val document = (filled("halves.pdf").open() as PdfOpened.Document).document

        val page = document.render(0, 300)!!.asAndroidBitmap()
        assertEquals(400, page.height) // 300 × 800 / 600
        assertDominant("red", page.getPixel(150, 20))
        assertDominant("blue", page.getPixel(150, 380))
        document.close()
    }

    /**
     * A 600×800 page, red over blue, with `/Rotate 90`: shown 800×600, turned clockwise — the page's top
     * (red) down the right-hand side. iOS is made to match what this pins.
     */
    @Test
    fun a_page_turned_a_quarter_is_shown_turned() {
        assumeAndroid11()
        val document = (filled("turned.pdf").open() as PdfOpened.Document).document

        assertEquals(PdfPageSize(800, 600), document.pageSize(0))
        val page = document.render(0, 400)!!.asAndroidBitmap()
        assertEquals(300, page.height) // 400 × 600 / 800
        assertDominant("red", page.getPixel(380, 150))
        assertDominant("blue", page.getPixel(20, 150))
        document.close()
    }

    /** A red page cropped to a green 400×600 rectangle: shown 400×600 and green to every edge. */
    @Test
    fun a_cropped_page_is_shown_as_its_crop_box() {
        assumeAndroid11()
        val document = (filled("turned.pdf").open() as PdfOpened.Document).document

        assertEquals(PdfPageSize(400, 600), document.pageSize(1))
        val page = document.render(1, 200)!!.asAndroidBitmap()
        assertEquals(300, page.height) // 200 × 600 / 400
        for ((x, y) in listOf(
            3 to 3,
            196 to 3,
            3 to 296,
            196 to 296,
            100 to 150,
        )) {
            assertDominant("green", page.getPixel(x, y))
        }
        document.close()
    }

    @Test
    fun a_password_protected_pdf_is_refused_as_needing_a_password_and_released() {
        assumeAndroid11()
        val before = memoryFilesOpen()
        assertEquals(PdfOpened.Refused(PdfRefusal.NeedsPassword), filled("locked.pdf").open())
        assertEquals("a refused file does not linger", before, memoryFilesOpen())
    }

    @Test
    fun a_truncated_pdf_is_refused_as_unreadable_and_released() {
        assumeAndroid11()
        val before = memoryFilesOpen()
        assertEquals(PdfOpened.Refused(PdfRefusal.Unreadable), filled("truncated.pdf").open())
        assertEquals("a refused file does not linger", before, memoryFilesOpen())
    }
}
