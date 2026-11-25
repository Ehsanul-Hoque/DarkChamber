package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.TextKind

/** The two faces a text page reads in. */
internal enum class TextFont {
    /** The app's own font, for prose. */
    Reading,

    /** Every character the same width, so indentation and columns line up. */
    Mono,
}

/**
 * A text's lines cut into rows to lay out: one row per line, except that a line longer than a row may
 * hold is cut into several. A line is wrapped on screen anyway, so the cut shows only as a wrap a
 * little early; without it one long line — a minified JSON file is a single line of up to 8 MiB — is
 * one item taller than a layout may be, and the app stops.
 */
internal class TextRows internal constructor(private val text: DecodedText.Text, private val packed: IntArray) {
    val count: Int get() = packed.size / 3

    /** The stored line row [index] belongs to, counted from 0. */
    fun lineOf(index: Int): Int = packed[3 * index]

    fun row(index: Int): String = text.whole.substring(packed[3 * index + 1], packed[3 * index + 2])

    /** The whole text, and where row [index] starts and ends in it — to read a row without copying it. */
    val whole: String get() = text.whole

    fun start(index: Int): Int = packed[3 * index + 1]

    fun end(index: Int): Int = packed[3 * index + 2]
}

/** What a text page draws. */
internal sealed interface TextView {
    /** One wrapped line per item: the file's lines as stored, or — [indented] — the JSON re-indented. */
    data class Lines(val indented: Boolean, val font: TextFont) : TextView

    /** A Markdown file drawn as blocks. */
    data object Markdown : TextView
}

/**
 * The rules of a text page, apart from drawing it. The page is a column of [rows]; rows are laid out
 * only as they come on screen, so the fast-scroll thumb stands by their heights as measured so far and
 * estimated for the rest ([RowHeights]).
 */
internal object TextReading {
    /**
     * What the page shows. Prose reads in the reading font, code-like files in monospace; a Markdown or
     * JSON file [canFormat] shows its formatted view while [formatted] is chosen, and otherwise its
     * lines as written in monospace — the plain view is for seeing the file's own characters.
     */
    fun view(kind: TextKind, canFormat: Boolean, formatted: Boolean): TextView {
        val asFormatted = formatted && hasSwitch(kind, canFormat)
        return when (kind) {
            TextKind.Plain -> TextView.Lines(indented = false, font = TextFont.Reading)
            TextKind.Code -> TextView.Lines(indented = false, font = TextFont.Mono)
            TextKind.Markdown -> if (asFormatted) TextView.Markdown else TextView.Lines(indented = false, font = TextFont.Mono)
            TextKind.Json -> TextView.Lines(indented = asFormatted, font = TextFont.Mono)
        }
    }

    /** Whether the Formatted / Plain switch shows: only on a Markdown or JSON file that has a formatted view. */
    fun hasSwitch(kind: TextKind, canFormat: Boolean): Boolean =
        canFormat && (kind == TextKind.Markdown || kind == TextKind.Json)

    /**
     * Whether the thumb is had now: the column's height is estimated from the rows laid out so far
     * ([RowHeights]), so whether it is long enough ([worthNow]) can change as more are seen — once shown
     * the thumb stays for the page, and while it is dragged it never goes.
     */
    fun keepsThumb(shownBefore: Boolean, worthNow: Boolean, dragging: Boolean): Boolean = shownBefore || worthNow || dragging

    /**
     * Whether a column is worth a thumb now. Formatted Markdown ([whenScrolls]) has one as soon as it can
     * scroll at all — known from its first layout, while its height is still a guess that could pass five
     * screens only late; the other pages, whose height is known before drawing, past five screens
     * ([FastScroll.worthIt]).
     */
    fun worthAThumb(whenScrolls: Boolean, canScroll: Boolean, contentHeight: Int, viewportHeight: Int): Boolean =
        if (whenScrolls) canScroll else FastScroll.worthIt(contentHeight, viewportHeight)

    /**
     * Whether a drag to [fraction] is the whole way down, which goes to the very end of the column — the
     * last row may be taller than the screen, and bringing its top to the top would leave its end unseen.
     */
    fun toTheEnd(fraction: Float): Boolean = fraction >= 1f

    /** The most characters one row holds: tall enough to read on, far short of the tallest item a layout allows. */
    const val ROW_MAX_CHARS = 2_000

    /**
     * [text]'s lines as rows of at most [maxChars] characters. A longer line is cut after the last
     * space in the final quarter of the room, so a word is not split when one ends near the cut;
     * otherwise at the room's end — never between the two halves of a character outside the basic
     * plane. Every character is in exactly one row, in order.
     */
    fun rows(text: DecodedText.Text, maxChars: Int = ROW_MAX_CHARS): TextRows {
        // Room for a whole character outside the basic plane, so every cut moves forward.
        require(maxChars >= 2)
        val whole = text.whole
        var packed = IntArray(3 * text.lineCount)
        var count = 0
        fun add(line: Int, start: Int, end: Int) {
            if (3 * count == packed.size) packed = packed.copyOf(packed.size * 2)
            packed[3 * count] = line
            packed[3 * count + 1] = start
            packed[3 * count + 2] = end
            count++
        }
        for (line in 0 until text.lineCount) {
            var start = text.start(line)
            val end = text.end(line)
            while (end - start > maxChars) {
                add(line, start, cutAt(whole, start, maxChars))
                start = packed[3 * count - 1]
            }
            add(line, start, end)
        }
        return TextRows(text, packed.copyOf(3 * count))
    }

    /**
     * Where a row of [whole] from [start] with room for [maxChars] ends, when more than that remains:
     * after a late space, or at the room's end. Shared with the Markdown page's long blocks.
     */
    fun cutAt(whole: String, start: Int, maxChars: Int): Int {
        val limit = start + maxChars
        // Only the last quarter is looked at: a search further back would read a minified file's one
        // long line again for every row cut from it.
        for (index in limit - 1 downTo limit - maxChars / 4) {
            if (whole[index] == ' ') return index + 1
        }
        return if (whole[limit - 1].isHighSurrogate()) limit - 1 else limit
    }

    /** What a screen reader says for a line: nothing for a blank one, which it then passes over. */
    fun spoken(line: String): String? = line.takeIf { it.isNotBlank() }
}
