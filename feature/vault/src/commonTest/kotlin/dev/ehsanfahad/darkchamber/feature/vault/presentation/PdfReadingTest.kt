package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules for reading a PDF's pages, each pinned to the case that tells it apart. */
class PdfReadingTest {
    // ---- the page indicator names the page most on screen ----------------------------------------

    @Test
    fun one_page_filling_the_screen_is_the_one_named() {
        val pages = listOf(LaidOutPage(index = 4, top = -100, height = 3000))
        assertEquals(4, PdfReading.mostOnScreen(pages, viewportTop = 0, viewportBottom = 2000))
    }

    /** Page 2 shows 1500 px, page 3 only 500: the indicator names page 2, not the first one listed. */
    @Test
    fun of_two_pages_the_one_showing_more_is_named() {
        val pages = listOf(LaidOutPage(2, top = -500, height = 2000), LaidOutPage(3, top = 1500, height = 2000))
        assertEquals(2, PdfReading.mostOnScreen(pages, 0, 2000))
        val later = listOf(LaidOutPage(2, top = -1500, height = 2000), LaidOutPage(3, top = 500, height = 2000))
        assertEquals(3, PdfReading.mostOnScreen(later, 0, 2000), "page 3 now shows 1500 px to page 2's 500")
    }

    /** A short page wholly on screen shows less than a tall one cut off: what counts is the pixels shown. */
    @Test
    fun what_counts_is_how_much_shows_not_whether_it_is_whole() {
        val pages = listOf(LaidOutPage(0, top = 0, height = 300), LaidOutPage(1, top = 320, height = 3000))
        assertEquals(1, PdfReading.mostOnScreen(pages, 0, 2000))
    }

    @Test
    fun an_even_split_names_the_earlier_page() {
        val pages = listOf(LaidOutPage(5, top = -1000, height = 2000), LaidOutPage(6, top = 1000, height = 2000))
        assertEquals(5, PdfReading.mostOnScreen(pages, 0, 2000))
    }

    /** "Earlier" means the lower page number, whatever order the layout lists them in. */
    @Test
    fun an_even_split_names_the_earlier_page_however_they_are_listed() {
        val pages = listOf(LaidOutPage(6, top = 1000, height = 2000), LaidOutPage(5, top = -1000, height = 2000))
        assertEquals(5, PdfReading.mostOnScreen(pages, 0, 2000))
    }

    /** The viewport need not start at 0 (a bar's padding): visibility is measured against its own edges. */
    @Test
    fun visibility_is_measured_against_the_viewport_edges() {
        val pages = listOf(LaidOutPage(0, top = 0, height = 1000), LaidOutPage(1, top = 1000, height = 1000))
        assertEquals(1, PdfReading.mostOnScreen(pages, viewportTop = 800, viewportBottom = 2000))
    }

    @Test
    fun nothing_laid_out_names_no_page() {
        assertNull(PdfReading.mostOnScreen(emptyList(), 0, 2000))
    }

    // ---- jumping to a page: what the field holds -------------------------------------------------

    /** People count pages from 1; the column counts from 0. */
    @Test
    fun a_page_number_goes_to_that_page_counted_from_one() {
        assertEquals(PageJump.Go(0), PdfReading.parseJump("1", pageCount = 12))
        assertEquals(PageJump.Go(11), PdfReading.parseJump("12", pageCount = 12))
        assertEquals(PageJump.Go(399), PdfReading.parseJump("400", pageCount = 427))
    }

    @Test
    fun spaces_around_the_number_and_leading_zeros_are_forgiven() {
        assertEquals(PageJump.Go(6), PdfReading.parseJump("  7 ", 12))
        assertEquals(PageJump.Go(6), PdfReading.parseJump("007", 12))
    }

    @Test
    fun an_empty_field_is_empty_not_an_error() {
        assertEquals(PageJump.Empty, PdfReading.parseJump("", 12))
        assertEquals(PageJump.Empty, PdfReading.parseJump("   ", 12))
    }

    /** Never clamped: a reader who typed 13 of 12 is told, not quietly taken to page 12. */
    @Test
    fun a_number_no_page_has_is_out_of_range_never_clamped() {
        assertEquals(PageJump.OutOfRange, PdfReading.parseJump("13", 12))
        assertEquals(PageJump.OutOfRange, PdfReading.parseJump("0", 12))
        assertEquals(PageJump.OutOfRange, PdfReading.parseJump("99999999999999999999", 12), "too long for any int")
    }

    // ---- what stays drawn: the pages on screen and one either side --------------------------------

    @Test
    fun the_pages_on_screen_and_one_either_side_are_drawn() {
        assertEquals(4..8, PdfReading.renderWindow(visible = 5..7, pageCount = 427))
    }

    @Test
    fun the_window_stops_at_the_ends_of_the_document() {
        assertEquals(0..2, PdfReading.renderWindow(0..1, 427))
        assertEquals(425..426, PdfReading.renderWindow(426..426, 427))
        assertEquals(0..0, PdfReading.renderWindow(0..0, 1), "a one-page document")
    }

    @Test
    fun nothing_on_screen_or_no_pages_draws_nothing() {
        assertEquals(IntRange.EMPTY, PdfReading.renderWindow(IntRange.EMPTY, 427))
        assertEquals(IntRange.EMPTY, PdfReading.renderWindow(0..0, 0))
    }

    // ---- how wide a page is drawn ----------------------------------------------------------------

    /** An A4 page (595 × 842 pt), the shape every portrait case below is about. */
    private fun a4(shownWidthPx: Int, zoom: Float, screenWidthPx: Int) =
        PdfReading.renderWidth(shownWidthPx, zoom, screenWidthPx, pageWidthPt = 595, pageHeightPt = 842)

    @Test
    fun an_unzoomed_page_is_drawn_at_the_width_it_is_shown() {
        assertEquals(1440, a4(shownWidthPx = 1440, zoom = 1f, screenWidthPx = 1440))
        assertEquals(1000, a4(1000, 1f, 1440), "a narrower column")
    }

    @Test
    fun a_zoomed_page_is_drawn_sharper_up_to_twice_the_screen_width() {
        assertEquals(2880, a4(1440, zoom = 2f, screenWidthPx = 1440))
        assertEquals(2880, a4(1440, zoom = 5f, screenWidthPx = 1440), "capped")
        assertEquals(2160, a4(1440, zoom = 1.5f, screenWidthPx = 1440))
        assertEquals(1502, a4(1001, zoom = 1.5f, screenWidthPx = 1440), "1501.5 rounds up")
    }

    @Test
    fun the_cap_is_twice_the_screen_width() {
        assertEquals(2, PdfReading.MAX_RENDER_SCREENS)
    }

    @Test
    fun a_page_is_never_drawn_at_no_width() {
        assertEquals(1, a4(0, 1f, 1440))
    }

    // ---- and never more pixels than one page may have ------------------------------------------------

    /** 64 MiB at 4 bytes a pixel: well inside the 100 MB Android draws in one go. */
    @Test
    fun one_page_may_have_sixteen_mebipixels() {
        assertEquals(16 * 1024 * 1024, PdfReading.MAX_RENDER_PIXELS)
        assertTrue(PdfReading.MAX_RENDER_PIXELS * 4L < 100L * 1024 * 1024)
    }

    /**
     * The crash: a phone on its side is 3120 px wide, so two screens let an A4 page be drawn 6240 × 8830
     * — 220,496,640 bytes, which Android refused to draw. Now the page stops where its pixels run out:
     * 3443 × 4872.
     */
    @Test
    fun a_page_zoomed_in_landscape_stops_at_the_pixel_limit() {
        val width = a4(3120, zoom = 4f, screenWidthPx = 3120)
        assertEquals(3443, width)
        assertTrue(width.toLong() * PdfReading.pageHeight(width, 595, 842) <= PdfReading.MAX_RENDER_PIXELS)
    }

    /** At fit in landscape an A4 page (3120 × 4415, 13.8 M pixels) is within the limit and drawn as shown. */
    @Test
    fun a_page_at_fit_in_landscape_is_drawn_as_shown() {
        assertEquals(3120, a4(3120, zoom = 1f, screenWidthPx = 3120))
    }

    /** A long receipt reaches the limit even at fit: drawn a little narrower than shown, never refused. */
    @Test
    fun a_very_tall_page_is_drawn_within_the_limit_even_at_fit() {
        val width = PdfReading.renderWidth(1440, 1f, 1440, pageWidthPt = 595, pageHeightPt = 5000)
        assertEquals(1412, width)
        assertTrue(width.toLong() * PdfReading.pageHeight(width, 595, 5000) <= PdfReading.MAX_RENDER_PIXELS)
    }

    /** A landscape-shaped page zoomed in landscape: 4872 × 3443, the widest within the limit. */
    @Test
    fun a_wide_page_zoomed_in_landscape_stops_at_the_pixel_limit() {
        assertEquals(4872, PdfReading.renderWidth(3120, 4f, 3120, pageWidthPt = 842, pageHeightPt = 595))
    }

    private fun pixels(width: Int, w: Int, h: Int) = width.toLong() * PdfReading.pageHeight(width, w, h)

    /**
     * Whatever the shape and the zoom, a drawn page never has more pixels than the limit — and where the
     * limit is what stopped it, it is the widest that fits: one pixel wider would be over. Sharpness is
     * not given away to rounding.
     */
    @Test
    fun a_page_is_drawn_as_wide_as_the_limit_allows_and_no_wider() {
        val shapes = (listOf(595 to 842, 842 to 595, 595 to 5000, 5000 to 595, 100 to 100, 612 to 792, 1 to 10_000) +
            (1..400).map { 300 + it * 7 to 400 + it * 13 })
        for ((w, h) in shapes) for (screen in listOf(1080, 1440, 2400, 3120)) for (zoom in listOf(1f, 2.5f, 4f)) {
            val wanted = minOf((screen * zoom).toInt(), screen * PdfReading.MAX_RENDER_SCREENS)
            val width = PdfReading.renderWidth(screen, zoom, screen, w, h)
            val case = "$w×$h pt on $screen px at $zoom: $width wide"
            assertTrue(pixels(width, w, h) <= PdfReading.MAX_RENDER_PIXELS, "$case, over the limit")
            if (width < wanted) assertTrue(pixels(width + 1, w, h) > PdfReading.MAX_RENDER_PIXELS, "$case, but ${width + 1} fits")
        }
    }

    /** A page with no size has no shape to measure: only the screen cap applies. */
    @Test
    fun a_page_with_no_size_is_capped_by_the_screen_alone() {
        assertEquals(2880, PdfReading.renderWidth(1440, 4f, 1440, pageWidthPt = 0, pageHeightPt = 0))
    }

    /** A page's slot is sized before it is drawn, from its own shape, so the column does not jump. */
    @Test
    fun a_page_slot_keeps_the_page_shape() {
        // Rounded to the nearest pixel: 1440 × 842 / 595 = 2037.8, 1440 × 595 / 842 = 1017.6.
        assertEquals(2038, PdfReading.pageHeight(widthPx = 1440, pageWidthPt = 595, pageHeightPt = 842))
        assertEquals(1018, PdfReading.pageHeight(1440, pageWidthPt = 842, pageHeightPt = 595), "landscape")
        assertEquals(0, PdfReading.pageHeight(1440, pageWidthPt = 0, pageHeightPt = 842), "a page with no width")
    }

    @Test
    fun anything_but_digits_is_not_a_number() {
        for (input in listOf("-3", "+3", "3.5", "three", "3 4", "৩.৫")) {
            assertEquals(PageJump.NotANumber, PdfReading.parseJump(input, 12), "\"$input\"")
        }
    }

    /** A Bangla or Arabic keyboard types its own digits: page ৩ is page 3. */
    @Test
    fun digits_in_another_script_are_page_numbers_too() {
        assertEquals(PageJump.Go(2), PdfReading.parseJump("৩", 12), "Bangla")
        assertEquals(PageJump.Go(11), PdfReading.parseJump("১২", 12), "Bangla, two digits")
        assertEquals(PageJump.Go(2), PdfReading.parseJump("٣", 12), "Arabic-Indic")
        assertEquals(PageJump.OutOfRange, PdfReading.parseJump("১৩", 12), "and still never clamped")
    }

    // ---- what the screen shows of a zoomed column ----------------------------------------------------

    @Test
    fun at_fit_the_screen_shows_the_whole_viewport() {
        assertEquals(ColumnBand(0, 2000), PdfReading.seenBand(heightPx = 2000, zoom = 1f, offsetY = 0f))
    }

    /** Twice the size about the centre: the middle half of the viewport fills the screen. */
    @Test
    fun zoomed_the_screen_shows_the_middle_of_the_viewport() {
        assertEquals(ColumnBand(500, 1500), PdfReading.seenBand(2000, zoom = 2f, offsetY = 0f))
    }

    /** Moved down by the whole overhang, the top of the viewport is at the top of the screen. */
    @Test
    fun moving_the_zoomed_column_moves_the_band_the_other_way() {
        assertEquals(ColumnBand(0, 1000), PdfReading.seenBand(2000, zoom = 2f, offsetY = 1000f))
        assertEquals(ColumnBand(900, 1400), PdfReading.seenBand(2000, zoom = 4f, offsetY = -600f), "moved up, deeper")
    }

    /** A band that ends between pixels takes the pixel in: a sliver of a page on screen is on screen. */
    @Test
    fun a_band_is_rounded_outwards() {
        // 1000 ∓ 1000/3 = 666.7 and 1333.3
        assertEquals(ColumnBand(666, 1334), PdfReading.seenBand(2000, zoom = 3f, offsetY = 0f))
    }

    private val threePages = listOf(
        LaidOutPage(0, top = 0, height = 1000),
        LaidOutPage(1, top = 1010, height = 1000),
        LaidOutPage(2, top = 2020, height = 1000),
    )

    @Test
    fun the_pages_seen_are_those_the_band_crosses() {
        assertEquals(0..1, PdfReading.pagesSeen(threePages, top = 500, bottom = 1500))
        assertEquals(0..2, PdfReading.pagesSeen(threePages, top = 0, bottom = 3020))
        assertEquals(2..2, PdfReading.pagesSeen(threePages, top = 2100, bottom = 2500))
    }

    /** A page that ends exactly where the band starts shows nothing of itself. */
    @Test
    fun a_page_touching_the_band_is_not_seen() {
        assertEquals(2..2, PdfReading.pagesSeen(threePages, top = 2010, bottom = 2500))
        assertEquals(0..0, PdfReading.pagesSeen(threePages, top = 0, bottom = 1010), "and at the bottom")
    }

    @Test
    fun a_band_in_the_gap_between_pages_sees_none() {
        assertEquals(IntRange.EMPTY, PdfReading.pagesSeen(threePages, top = 1001, bottom = 1009))
        assertEquals(IntRange.EMPTY, PdfReading.pagesSeen(emptyList(), top = 0, bottom = 2000), "nothing laid out")
    }

    @Test
    fun the_pages_seen_do_not_depend_on_the_order_listed() {
        assertEquals(1..2, PdfReading.pagesSeen(threePages.reversed(), top = 1500, bottom = 2500))
    }

    // ---- a drag on a zoomed column ------------------------------------------------------------------

    @Test
    fun within_the_overhang_a_drag_only_moves_the_zoomed_column() {
        assertEquals(PanSplit(offsetY = 300f, scrollBy = 0f), PdfReading.panThenScroll(0f, dy = 300f, limitY = 500f, zoom = 2f))
        assertEquals(PanSplit(350f, 0f), PdfReading.panThenScroll(450f, dy = -100f, limitY = 500f, zoom = 2f))
    }

    /**
     * Past the overhang the rest scrolls the column, in its own pixels: a finger moving down 200 screen
     * pixels at 2x is 100 of the column's, back towards the start.
     */
    @Test
    fun past_the_overhang_the_rest_of_a_drag_scrolls_the_column() {
        assertEquals(PanSplit(500f, scrollBy = -100f), PdfReading.panThenScroll(400f, dy = 300f, limitY = 500f, zoom = 2f))
        assertEquals(PanSplit(-500f, scrollBy = 150f), PdfReading.panThenScroll(-500f, dy = -300f, limitY = 500f, zoom = 2f), "on, up the column")
        assertEquals(PanSplit(-500f, scrollBy = 75f), PdfReading.panThenScroll(-500f, dy = -300f, limitY = 500f, zoom = 4f), "slower, deeper in")
    }
}
