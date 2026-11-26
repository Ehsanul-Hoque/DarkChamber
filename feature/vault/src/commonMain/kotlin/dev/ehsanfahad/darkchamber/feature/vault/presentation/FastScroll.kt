package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPlace
import kotlin.math.roundToInt

/**
 * The rules for a long column's fast-scroll thumb — a PDF's pages or a text file's rows. All in the
 * column's own pixels: the thumb runs along a track (from the top bar's bottom to the bottom bar's top)
 * and stands where the scroll stands between the top and the end, so it measures the real height, not
 * the item count — a book of mixed page sizes, or a document of short lines and tall tables, puts the
 * thumb where the reader actually is. For a PDF the column is page slots stacked with a gap, scrolled by
 * `s` = the top of the first page shown plus how far down it the screen starts ([tops], [placeAt]); a text
 * page measures its rows as they are drawn ([RowHeights]) and uses the track and drag rules here.
 */
internal object FastScroll {
    /** How many screens tall a column must be for a thumb to be worth having. */
    const val MIN_SCREENS = 5

    /** Each page's top in the column, from the slot heights and the gap between slots. */
    fun tops(
        heights: List<Int>,
        gapPx: Int,
    ): List<Int> {
        var next = 0
        return heights.map { height -> next.also { next += height + gapPx } }
    }

    /** The column's height: every slot and every gap between two. */
    fun contentHeight(
        heights: List<Int>,
        gapPx: Int,
    ): Int = if (heights.isEmpty()) 0 else heights.sum() + gapPx * (heights.size - 1)

    /** How far the column can scroll: its height and padding, less the screen. Never below 0. */
    fun maxScroll(
        contentHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
        viewportHeight: Int,
    ): Int = (contentHeight + paddingTop + paddingBottom - viewportHeight).coerceAtLeast(0)

    /** Whether the column is long enough for a thumb: taller than [MIN_SCREENS] screens. */
    fun worthIt(
        contentHeight: Int,
        viewportHeight: Int,
    ): Boolean = contentHeight > viewportHeight * MIN_SCREENS

    /**
     * How far through the column the screen is, 0 at the top to 1 at the end, with page [first] the
     * first shown, [offset] pixels down it. A column that cannot scroll is at 0.
     */
    fun fraction(
        tops: List<Int>,
        first: Int,
        offset: Int,
        maxScroll: Int,
    ): Float {
        if (maxScroll <= 0) return 0f
        val scrolled = (tops.getOrElse(first) { 0 } + offset).toFloat()
        return (scrolled / maxScroll).coerceIn(0f, 1f)
    }

    /** The thumb's top, [thumbHeight] tall, standing at [fraction] of the track. */
    fun thumbTop(
        fraction: Float,
        trackTop: Int,
        trackBottom: Int,
        thumbHeight: Int,
    ): Int {
        val travel = (trackBottom - trackTop - thumbHeight).coerceAtLeast(0)
        return trackTop + (fraction * travel).roundToInt()
    }

    /** The fraction a thumb dragged to [thumbTop] stands for — the inverse of [thumbTop], kept in 0..1. */
    fun fractionAt(
        thumbTop: Int,
        trackTop: Int,
        trackBottom: Int,
        thumbHeight: Int,
    ): Float {
        val travel = trackBottom - trackTop - thumbHeight
        if (travel <= 0) return 0f
        return ((thumbTop - trackTop).toFloat() / travel).coerceIn(0f, 1f)
    }

    /** Where [fraction] of the way through the column is: the page there, and how far down it. */
    fun placeAt(
        fraction: Float,
        tops: List<Int>,
        maxScroll: Int,
    ): PdfPlace {
        if (tops.isEmpty()) return PdfPlace()
        val scrolled = (fraction * maxScroll).roundToInt()
        // The last page starting at or above that point: a point in a gap belongs to the page above it.
        val page = tops.indexOfLast { it <= scrolled }.coerceAtLeast(0)
        return PdfPlace(page = page, offsetPx = scrolled - tops[page])
    }

    /**
     * Whether a press at ([x], [y]) takes the thumb: within the strip [stripWidth] wide along the right
     * edge of a box [width] wide, and within [slop] of the thumb's height. Anything else is the column's
     * and the pager's.
     */
    fun grabs(
        x: Float,
        y: Float,
        width: Int,
        stripWidth: Int,
        thumbTop: Int,
        thumbHeight: Int,
        slop: Int,
    ): Boolean = x >= width - stripWidth && y >= thumbTop - slop && y <= thumbTop + thumbHeight + slop

    /**
     * Whether the thumb shows: only on a column [worthIt], never zoomed in (a drag there pans the page),
     * and then while the bars are shown, while the column scrolled lately, or while it is being dragged.
     */
    fun visible(
        worthIt: Boolean,
        zoomedIn: Boolean,
        barsShown: Boolean,
        scrolledLately: Boolean,
        dragging: Boolean,
    ): Boolean = worthIt && !zoomedIn && (barsShown || scrolledLately || dragging)
}
