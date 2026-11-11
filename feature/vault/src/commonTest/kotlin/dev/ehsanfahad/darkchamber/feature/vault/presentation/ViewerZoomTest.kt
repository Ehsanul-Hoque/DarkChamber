package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The viewer's zoom and pan arithmetic, kept out of the composable so it can be checked. Expected
 * values are worked from what the user sees — the point under a finger stays under it — not from the
 * formula.
 */
class ViewerZoomTest {
    private val box = Size(1_000f, 2_000f)
    private val center = Offset(500f, 1_000f)

    @Test
    fun a_picture_is_fitted_inside_the_box_keeping_its_shape() {
        assertEquals(Size(1_000f, 750f), ViewerZoom.fitted(Size(4_000f, 3_000f), box), "landscape: width-bound")
        assertEquals(Size(1_000f, 2_000f), ViewerZoom.fitted(Size(500f, 1_000f), box), "same shape: fills, enlarged")
        assertEquals(Size(500f, 2_000f), ViewerZoom.fitted(Size(1_000f, 4_000f), box), "tall: height-bound")
    }

    @Test
    fun a_pinch_stays_between_fit_and_four_times() {
        assertEquals(2f, ViewerZoom.afterPinch(1f, 2f))
        assertEquals(4f, ViewerZoom.afterPinch(3f, 2f), "never past 4x")
        assertEquals(1f, ViewerZoom.afterPinch(1.2f, 0.5f), "never smaller than the fitted picture")
    }

    @Test
    fun a_double_tap_zooms_in_from_fit_and_back_out_from_anywhere_else() {
        assertEquals(ViewerZoom.DOUBLE_TAP, ViewerZoom.afterDoubleTap(1f))
        assertEquals(1f, ViewerZoom.afterDoubleTap(ViewerZoom.DOUBLE_TAP))
        assertEquals(1f, ViewerZoom.afterDoubleTap(3.7f))
    }

    /**
     * The picture is scaled about the box's centre and then moved by the offset, so a screen point s
     * shows content point (s - center - offset) / zoom. A pinch must keep whatever is under the
     * fingers' centroid where it is.
     */
    @Test
    fun a_pinch_keeps_the_point_under_the_fingers_where_it_is() {
        val centroid = Offset(800f, 400f)
        val offset = Offset(30f, -20f)
        val zoom = 1.5f
        val newZoom = 3f
        fun contentUnder(s: Offset, o: Offset, z: Float) = (s - center - o) / z

        val newOffset = ViewerZoom.offsetAfterPinch(offset, zoom, newZoom, centroid, center, pan = Offset.Zero)

        assertClose(contentUnder(centroid, offset, zoom), contentUnder(centroid, newOffset, newZoom))
    }

    @Test
    fun a_pinch_that_moves_also_pans_by_that_much() {
        val moved = ViewerZoom.offsetAfterPinch(Offset.Zero, 2f, 2f, center, center, pan = Offset(15f, -5f))
        assertClose(Offset(15f, -5f), moved)
    }

    /** A double tap zooms toward where it landed: that point comes to the centre, as far as the edges allow. */
    @Test
    fun a_double_tap_brings_the_tapped_point_toward_the_centre() {
        val tap = Offset(700f, 1_100f)
        val offset = ViewerZoom.offsetForDoubleTapAt(tap, center, newZoom = 2.5f)
        // The content that was under the tap at fit (offset 0, zoom 1) is now drawn at the centre.
        val contentAtTap = tap - center
        assertClose(center, center + offset + contentAtTap * 2.5f)
    }

    /**
     * Panning stops at the picture's edges: the most it can move is how far the zoomed picture
     * overhangs the box on that axis, and an axis it does not overhang cannot move at all.
     */
    @Test
    fun panning_stops_where_the_picture_ends() {
        val content = Size(1_000f, 750f) // a landscape photo fitted to the box's width
        // At 2x: 2000 x 1500 in a 1000 x 2000 box — 500 spare either side horizontally, none vertically.
        assertClose(Offset(500f, 0f), ViewerZoom.clampOffset(Offset(900f, 300f), 2f, content, box))
        assertClose(Offset(-500f, 0f), ViewerZoom.clampOffset(Offset(-900f, -300f), 2f, content, box))
        // At 4x: 4000 x 3000 — 1500 spare horizontally, 500 vertically.
        assertClose(Offset(1_500f, -500f), ViewerZoom.clampOffset(Offset(2_000f, -800f), 4f, content, box))
        assertClose(Offset(100f, 200f), ViewerZoom.clampOffset(Offset(100f, 200f), 4f, content, box), "within: untouched")
        assertClose(Offset.Zero, ViewerZoom.clampOffset(Offset(100f, 200f), 1f, content, box), "at fit nothing moves")
    }

    /** Zoomed in, a horizontal drag pans the picture; only at fit does it turn the page. */
    @Test
    fun the_pager_swipes_only_at_fit() {
        assertTrue(ViewerZoom.pagerMaySwipe(1f))
        assertFalse(ViewerZoom.pagerMaySwipe(1.01f))
    }

    private fun assertClose(expected: Offset, actual: Offset, what: String = "") {
        assertTrue(
            kotlin.math.abs(expected.x - actual.x) < 0.01f && kotlin.math.abs(expected.y - actual.y) < 0.01f,
            "$what expected $expected but was $actual",
        )
    }
}
