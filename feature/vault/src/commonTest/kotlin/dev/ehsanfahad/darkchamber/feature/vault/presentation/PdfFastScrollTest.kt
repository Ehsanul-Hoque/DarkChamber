package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fast-scroll thumb's rules, each pinned to the case that tells it apart. */
class PdfFastScrollTest {
    // Three pages of mixed height with a 10 px gap: tops 0, 1010, 1520; 2520 tall.
    private val heights = listOf(1000, 500, 1000)
    private val tops = listOf(0, 1010, 1520)

    // ---- the column ---------------------------------------------------------------------------------

    @Test
    fun each_page_starts_after_the_ones_above_and_their_gaps() {
        assertEquals(tops, PdfFastScroll.tops(heights, gapPx = 10))
        assertEquals(listOf(0), PdfFastScroll.tops(listOf(700), gapPx = 10), "one page")
        assertEquals(emptyList(), PdfFastScroll.tops(emptyList(), gapPx = 10))
    }

    /** A gap between every two slots, none after the last. */
    @Test
    fun the_column_is_every_slot_and_the_gaps_between() {
        assertEquals(2520, PdfFastScroll.contentHeight(heights, gapPx = 10))
        assertEquals(700, PdfFastScroll.contentHeight(listOf(700), gapPx = 10), "one page, no gap")
        assertEquals(0, PdfFastScroll.contentHeight(emptyList(), gapPx = 10))
    }

    @Test
    fun the_column_scrolls_by_its_height_and_padding_less_the_screen() {
        assertEquals(1820, PdfFastScroll.maxScroll(2520, paddingTop = 100, paddingBottom = 200, viewportHeight = 1000))
        assertEquals(0, PdfFastScroll.maxScroll(500, paddingTop = 100, paddingBottom = 200, viewportHeight = 1000), "shorter than the screen")
    }

    /** More than five screens tall, not five exactly: a short PDF scrolls fine by hand. */
    @Test
    fun only_a_column_taller_than_five_screens_gets_a_thumb() {
        assertTrue(PdfFastScroll.worthIt(contentHeight = 5_001, viewportHeight = 1_000))
        assertFalse(PdfFastScroll.worthIt(contentHeight = 5_000, viewportHeight = 1_000))
        assertFalse(PdfFastScroll.worthIt(contentHeight = 1_200, viewportHeight = 1_000))
    }

    // ---- where the thumb stands ---------------------------------------------------------------------

    /** 300 px into page 2, which starts at 1010: 1310 of 1820 — by height, not by page count. */
    @Test
    fun the_fraction_is_how_far_down_the_column_the_screen_is() {
        assertEquals(1310f / 1820f, PdfFastScroll.fraction(tops, first = 1, offset = 300, maxScroll = 1820))
        assertEquals(0f, PdfFastScroll.fraction(tops, first = 0, offset = 0, maxScroll = 1820), "the top")
    }

    @Test
    fun the_fraction_stays_between_the_top_and_the_end() {
        assertEquals(1f, PdfFastScroll.fraction(tops, first = 2, offset = 900, maxScroll = 1820), "past the end")
        assertEquals(0f, PdfFastScroll.fraction(tops, first = 1, offset = 0, maxScroll = 0), "a column that cannot scroll")
    }

    /** The thumb's top runs from the track's top to where the thumb's bottom meets the track's bottom. */
    @Test
    fun the_thumb_stands_at_its_fraction_of_the_track() {
        assertEquals(100, PdfFastScroll.thumbTop(0f, trackTop = 100, trackBottom = 1100, thumbHeight = 100))
        assertEquals(1000, PdfFastScroll.thumbTop(1f, trackTop = 100, trackBottom = 1100, thumbHeight = 100))
        assertEquals(550, PdfFastScroll.thumbTop(0.5f, trackTop = 100, trackBottom = 1100, thumbHeight = 100))
        assertEquals(400, PdfFastScroll.thumbTop(0.333f, trackTop = 100, trackBottom = 1100, thumbHeight = 100), "rounded: 399.7")
    }

    @Test
    fun a_dragged_thumb_stands_for_the_fraction_it_is_at() {
        assertEquals(0.5f, PdfFastScroll.fractionAt(thumbTop = 550, trackTop = 100, trackBottom = 1100, thumbHeight = 100))
        assertEquals(0f, PdfFastScroll.fractionAt(thumbTop = 50, trackTop = 100, trackBottom = 1100, thumbHeight = 100), "above the track")
        assertEquals(1f, PdfFastScroll.fractionAt(thumbTop = 2_000, trackTop = 100, trackBottom = 1100, thumbHeight = 100), "below it")
        assertEquals(0f, PdfFastScroll.fractionAt(thumbTop = 100, trackTop = 100, trackBottom = 180, thumbHeight = 100), "a track shorter than the thumb")
    }

    // ---- where a drag takes the column --------------------------------------------------------------

    @Test
    fun a_fraction_is_a_page_and_how_far_down_it() {
        assertEquals(PdfPlace(page = 1, offsetPx = 300), PdfFastScroll.placeAt(1310f / 1820f, tops, maxScroll = 1820))
        assertEquals(PdfPlace(page = 0, offsetPx = 0), PdfFastScroll.placeAt(0f, tops, maxScroll = 1820), "the top")
        assertEquals(PdfPlace(page = 2, offsetPx = 300), PdfFastScroll.placeAt(1f, tops, maxScroll = 1820), "the end")
    }

    /** In the gap after a page, the place is still that page — further down it than its height. */
    @Test
    fun a_place_in_a_gap_belongs_to_the_page_above() {
        assertEquals(PdfPlace(page = 0, offsetPx = 1005), PdfFastScroll.placeAt(1005f / 1820f, tops, maxScroll = 1820))
    }

    /** Exactly at a page's top is that page, not the one before it. */
    @Test
    fun a_page_top_belongs_to_that_page() {
        assertEquals(PdfPlace(page = 1, offsetPx = 0), PdfFastScroll.placeAt(1010f / 1820f, tops, maxScroll = 1820))
    }

    /** 0.6 of 1821 px is 1092.6: the nearest pixel, 1093 — 83 down page 2, not 82. */
    @Test
    fun a_place_between_pixels_is_the_nearest_one() {
        assertEquals(PdfPlace(page = 1, offsetPx = 83), PdfFastScroll.placeAt(0.6f, tops, maxScroll = 1821))
    }

    @Test
    fun nothing_laid_out_is_the_start() {
        assertEquals(PdfPlace(0, 0), PdfFastScroll.placeAt(0.5f, emptyList(), maxScroll = 0))
    }

    // ---- touching it --------------------------------------------------------------------------------

    private fun grabs(x: Float, y: Float) =
        PdfFastScroll.grabs(x, y, width = 1440, stripWidth = 168, thumbTop = 500, thumbHeight = 160, slop = 24)

    @Test
    fun a_press_on_the_thumb_takes_it() {
        assertTrue(grabs(1400f, 560f))
        assertTrue(grabs(1272f, 560f), "the strip's left edge")
    }

    /** Within the slop above and below the thumb, and no further. */
    @Test
    fun a_press_just_off_the_thumb_still_takes_it() {
        assertTrue(grabs(1400f, 476f), "24 above")
        assertFalse(grabs(1400f, 475f))
        assertTrue(grabs(1400f, 684f), "24 below")
        assertFalse(grabs(1400f, 685f))
    }

    /** Left of the strip is the page's: the column scrolls and the pager turns as ever. */
    @Test
    fun a_press_beside_the_strip_is_the_pages() {
        assertFalse(grabs(1271f, 560f))
        assertFalse(grabs(700f, 560f))
    }

    // ---- when it shows ------------------------------------------------------------------------------

    @Test
    fun the_thumb_shows_with_the_bars_after_a_scroll_and_while_dragged() {
        assertTrue(PdfFastScroll.visible(worthIt = true, zoomedIn = false, barsShown = true, scrolledLately = false, dragging = false))
        assertTrue(PdfFastScroll.visible(worthIt = true, zoomedIn = false, barsShown = false, scrolledLately = true, dragging = false))
        assertTrue(PdfFastScroll.visible(worthIt = true, zoomedIn = false, barsShown = false, scrolledLately = false, dragging = true))
    }

    /** Reading, with the bars put away and the column still: nothing over the page. */
    @Test
    fun the_thumb_hides_while_reading() {
        assertFalse(PdfFastScroll.visible(worthIt = true, zoomedIn = false, barsShown = false, scrolledLately = false, dragging = false))
    }

    @Test
    fun a_short_pdf_or_a_zoomed_one_has_no_thumb() {
        assertFalse(PdfFastScroll.visible(worthIt = false, zoomedIn = false, barsShown = true, scrolledLately = true, dragging = false), "short")
        assertFalse(PdfFastScroll.visible(worthIt = true, zoomedIn = true, barsShown = true, scrolledLately = true, dragging = false), "zoomed")
    }
}
