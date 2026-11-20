package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A page of a PDF as laid out on screen: its index, its top edge and its height, in pixels. */
internal data class LaidOutPage(val index: Int, val top: Int, val height: Int)

/** A band of the column, top to bottom, in the column's own (unzoomed) pixels. */
internal data class ColumnBand(val top: Int, val bottom: Int)

/**
 * A drag while zoomed, split: what moves the zoomed column on screen ([offsetY], its new vertical
 * offset), and what is left to scroll it ([scrollBy], in the column's own pixels).
 */
internal data class PanSplit(val offsetY: Float, val scrollBy: Float)

/** What the jump-to-page field holds, read. */
internal sealed interface PageJump {
    /** Go to the page at this zero-based index. */
    data class Go(val index: Int) : PageJump

    data object Empty : PageJump

    data object NotANumber : PageJump

    /** A number, but no page has it: the field says so, and nothing moves. */
    data object OutOfRange : PageJump
}

/** The rules for reading a PDF's pages: which page the indicator names, jumping, what to keep drawn. */
internal object PdfReading {
    /** How much wider than the screen one page is ever drawn, however far it is zoomed. */
    const val MAX_RENDER_SCREENS = 2

    /**
     * The page the indicator names: the one showing the most pixels between [viewportTop] and
     * [viewportBottom] — not the first listed, nor the first whole one. An even split names the
     * earlier page. `null` while nothing is laid out.
     */
    fun mostOnScreen(pages: List<LaidOutPage>, viewportTop: Int, viewportBottom: Int): Int? {
        var best: LaidOutPage? = null
        var bestShown = -1
        for (page in pages.sortedBy { it.index }) {
            val shown = minOf(page.top + page.height, viewportBottom) - maxOf(page.top, viewportTop)
            if (shown > bestShown) {
                best = page
                bestShown = shown
            }
        }
        return best?.index
    }

    /**
     * What the jump-to-page field holds, as a page counted from 1. Spaces around it and leading zeros
     * are forgiven, and any script's decimal digits count — a Bangla keyboard types ৩ for 3; anything
     * else (a sign, a decimal point, a letter) is not a number; a number no page has is
     * [PageJump.OutOfRange] — never clamped to the nearest page, which would take the reader somewhere
     * they did not ask for without saying so.
     */
    fun parseJump(input: String, pageCount: Int): PageJump {
        val text = input.trim()
        if (text.isEmpty()) return PageJump.Empty
        if (text.any { !it.isDigit() }) return PageJump.NotANumber
        val number = text.toIntOrNull() ?: return PageJump.OutOfRange
        return if (number in 1..pageCount) PageJump.Go(number - 1) else PageJump.OutOfRange
    }

    /**
     * The pages kept drawn: those on screen and one either side, clipped to the document. A drawn page
     * is a full-width bitmap (about 11 MiB at 1440 px), so nothing further is kept.
     */
    fun renderWindow(visible: IntRange, pageCount: Int): IntRange {
        // No pages clips to an empty range by itself; only nothing on screen needs saying.
        if (visible.isEmpty()) return IntRange.EMPTY
        return (visible.first - 1).coerceAtLeast(0)..(visible.last + 1).coerceAtMost(pageCount - 1)
    }

    /**
     * The width to draw a page at: the width it is shown, sharpened by [zoom], never past
     * [MAX_RENDER_SCREENS] screens wide — beyond that one page's bitmap would outgrow the memory the
     * rest of the window needs. At least one pixel.
     */
    fun renderWidth(shownWidthPx: Int, zoom: Float, screenWidthPx: Int): Int =
        (shownWidthPx * zoom).roundToInt()
            .coerceAtMost(screenWidthPx * MAX_RENDER_SCREENS)
            .coerceAtLeast(1)

    /** A page's height when shown [widthPx] wide, keeping its own shape; 0 for a page with no width. */
    fun pageHeight(widthPx: Int, pageWidthPt: Int, pageHeightPt: Int): Int {
        if (pageWidthPt <= 0) return 0
        return (widthPx.toLong() * pageHeightPt / pageWidthPt.toDouble()).roundToInt()
    }

    /**
     * The band of the column the screen shows, [heightPx] tall, zoomed by [zoom] about its centre and
     * moved by [offsetY] (the [ViewerZoom] model). At fit it is the whole viewport; zoomed, the part
     * under the screen — so the indicator names the page being read, and the pages drawn are those seen.
     */
    fun seenBand(heightPx: Int, zoom: Float, offsetY: Float): ColumnBand {
        // A screen point s shows the column's point (s - centre - offset) / zoom + centre.
        val centre = heightPx / 2f
        val top = centre - (centre + offsetY) / zoom
        val bottom = centre + (centre - offsetY) / zoom
        return ColumnBand(floor(top).toInt(), ceil(bottom).toInt())
    }

    /** The pages showing any of the band from [top] to [bottom], lowest to highest; empty if none. */
    fun pagesSeen(pages: List<LaidOutPage>, top: Int, bottom: Int): IntRange {
        val seen = pages.filter { it.top < bottom && it.top + it.height > top }.map { it.index }
        return if (seen.isEmpty()) IntRange.EMPTY else seen.min()..seen.max()
    }

    /**
     * A vertical drag of [dy] screen pixels on the column zoomed by [zoom]: it first moves the zoomed
     * column as far as [limitY] allows (so the page under the finger follows it to the screen's edge),
     * and whatever is left scrolls the column on — through the pages, at the zoomed speed.
     */
    fun panThenScroll(offsetY: Float, dy: Float, limitY: Float, zoom: Float): PanSplit {
        val moved = (offsetY + dy).coerceIn(-limitY, limitY)
        // What the move could not take, the other way round: a finger moving down past the overhang
        // shows what is above, so the column scrolls back. (Written so no drag gives -0.)
        return PanSplit(moved, (moved - offsetY - dy) / zoom)
    }
}
