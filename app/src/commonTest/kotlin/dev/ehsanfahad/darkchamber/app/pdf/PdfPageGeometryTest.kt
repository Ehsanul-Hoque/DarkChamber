package dev.ehsanfahad.darkchamber.app.pdf

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a PDF page is shown from its crop box and rotation. Each case follows corners of the crop box —
 * where the page's own top-left lands is what tells a turned page from a mirrored or unturned one.
 */
class PdfPageGeometryTest {
    // ---- rotation ---------------------------------------------------------------------------------

    @Test
    fun a_rotation_is_one_of_the_four_quarters() {
        assertEquals(0, PdfPageGeometry.rotation(0))
        assertEquals(90, PdfPageGeometry.rotation(90))
        assertEquals(180, PdfPageGeometry.rotation(180))
        assertEquals(270, PdfPageGeometry.rotation(270))
    }

    /** The PDF allows any multiple of 90, negative or past a whole turn. */
    @Test
    fun any_multiple_of_ninety_comes_back_to_a_quarter() {
        assertEquals(0, PdfPageGeometry.rotation(360))
        assertEquals(90, PdfPageGeometry.rotation(450))
        assertEquals(270, PdfPageGeometry.rotation(-90))
        assertEquals(180, PdfPageGeometry.rotation(-180))
    }

    // ---- size -------------------------------------------------------------------------------------

    @Test
    fun a_page_turned_a_quarter_swaps_its_width_and_height() {
        assertEquals(800.0 to 600.0, PdfPageGeometry.shownSize(600.0, 800.0, 90))
        assertEquals(800.0 to 600.0, PdfPageGeometry.shownSize(600.0, 800.0, 270))
    }

    @Test
    fun a_page_turned_upside_down_or_not_at_all_keeps_its_shape() {
        assertEquals(600.0 to 800.0, PdfPageGeometry.shownSize(600.0, 800.0, 0))
        assertEquals(600.0 to 800.0, PdfPageGeometry.shownSize(600.0, 800.0, 180))
    }

    // ---- where the page lands ---------------------------------------------------------------------

    // A crop box 400 × 600 pt at (100, 50) in its page; bitmaps sized to its shown shape at 2 px a point.
    private fun corners(rotation: Int, widthPx: Int, heightPx: Int): Map<String, Pair<Double, Double>> {
        val t = PdfPageGeometry.transform(100.0, 50.0, 400.0, 600.0, rotation, widthPx, heightPx)
        return mapOf(
            "bottom-left" to t.apply(100.0, 50.0),
            "top-left" to t.apply(100.0, 650.0),
            "top-right" to t.apply(500.0, 650.0),
            "bottom-right" to t.apply(500.0, 50.0),
        )
    }

    private fun assertAt(expected: Pair<Double, Double>, actual: Pair<Double, Double>?, what: String) {
        val (x, y) = actual!!
        assertTrue(abs(x - expected.first) < 1e-9 && abs(y - expected.second) < 1e-9, "$what: expected $expected, was $actual")
    }

    /** Unturned: the crop box fills the bitmap, its origin at the bitmap's — the bleed outside it falls off. */
    @Test
    fun an_unturned_crop_box_fills_the_bitmap() {
        val c = corners(0, widthPx = 800, heightPx = 1200)
        assertAt(0.0 to 0.0, c["bottom-left"], "bottom-left")
        assertAt(0.0 to 1200.0, c["top-left"], "top-left")
        assertAt(800.0 to 1200.0, c["top-right"], "top-right")
        assertAt(800.0 to 0.0, c["bottom-right"], "bottom-right")
    }

    /** Turned clockwise a quarter: the page's top runs down the bitmap's right edge. */
    @Test
    fun a_quarter_turn_puts_the_pages_top_on_the_right() {
        val c = corners(90, widthPx = 1200, heightPx = 800)
        assertAt(1200.0 to 800.0, c["top-left"], "top-left")
        assertAt(1200.0 to 0.0, c["top-right"], "top-right")
        assertAt(0.0 to 0.0, c["bottom-right"], "bottom-right")
        assertAt(0.0 to 800.0, c["bottom-left"], "bottom-left")
    }

    @Test
    fun a_half_turn_puts_the_page_upside_down() {
        val c = corners(180, widthPx = 800, heightPx = 1200)
        assertAt(800.0 to 0.0, c["top-left"], "top-left")
        assertAt(0.0 to 0.0, c["top-right"], "top-right")
        assertAt(0.0 to 1200.0, c["bottom-right"], "bottom-right")
        assertAt(800.0 to 1200.0, c["bottom-left"], "bottom-left")
    }

    /** Three quarters clockwise (a quarter anticlockwise): the page's top runs down the left edge. */
    @Test
    fun three_quarters_puts_the_pages_top_on_the_left() {
        val c = corners(270, widthPx = 1200, heightPx = 800)
        assertAt(0.0 to 0.0, c["top-left"], "top-left")
        assertAt(0.0 to 800.0, c["top-right"], "top-right")
        assertAt(1200.0 to 800.0, c["bottom-right"], "bottom-right")
        assertAt(1200.0 to 0.0, c["bottom-left"], "bottom-left")
    }

    /** A bitmap a pixel off the page's exact shape: each axis is scaled on its own, so the box still fills it. */
    @Test
    fun each_axis_fills_its_own_side() {
        val c = corners(90, widthPx = 1201, heightPx = 799)
        assertAt(1201.0 to 799.0, c["top-left"], "top-left")
        assertAt(0.0 to 0.0, c["bottom-right"], "bottom-right")
    }
}
