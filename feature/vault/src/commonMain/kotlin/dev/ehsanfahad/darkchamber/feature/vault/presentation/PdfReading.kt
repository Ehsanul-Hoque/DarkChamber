package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.math.roundToInt

/** A page of a PDF as laid out on screen: its index, its top edge and its height, in pixels. */
internal data class LaidOutPage(val index: Int, val top: Int, val height: Int)

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
}
