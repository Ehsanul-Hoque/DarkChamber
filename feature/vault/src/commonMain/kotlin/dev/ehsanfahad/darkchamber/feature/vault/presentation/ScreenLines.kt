package dev.ehsanfahad.darkchamber.feature.vault.presentation

/**
 * How wide each character of a page's text draws, in px, for one font at one size — measured once per
 * distinct character. Built from decrypted text (which characters a file holds), so it lives with the page
 * and never in screen state.
 */
internal class LetterWidths(codePoints: IntArray, widths: FloatArray) {
    /** The basic plane by index (NaN = not measured), the rest by code point. */
    private val basic = FloatArray(BASIC_PLANE) { Float.NaN }
    private val beyond = HashMap<Int, Float>()
    private val widest = widths.maxOrNull() ?: 0f

    init {
        codePoints.forEachIndexed { i, code ->
            if (code < BASIC_PLANE) basic[code] = widths[i] else beyond[code] = widths[i]
        }
    }

    /** The width of [codePoint]; one never measured counts as the widest measured. */
    fun of(codePoint: Int): Float {
        val width = if (codePoint < BASIC_PLANE) basic[codePoint] else beyond[codePoint] ?: Float.NaN
        return if (width.isNaN()) widest else width
    }

    override fun toString(): String = "LetterWidths"
}

/**
 * A row's height on screen, estimated before it is laid out, so a plain page knows its whole height from
 * the start: the row is wrapped as a line breaker fills lines — word by word at spaces, a word wider than a
 * line broken between characters, spaces at a line's end hanging past it — over each character's measured
 * width. An estimate: the platform's own line breaker also breaks at hyphens and between ideographs, and
 * shapes some scripts into fewer, wider glyphs; the measured height replaces it once the row is laid out.
 */
internal object ScreenLines {

    /**
     * How many characters the work walks between two calls of its stop check — counted in characters, not
     * rows, so the gap stays short however long the rows are (a minified file is rows of 2,000).
     */
    const val STOP_EVERY_CHARS = 65_536

    /**
     * Every distinct character (code point) in [text], ascending — what to measure. [stop] is called every
     * [STOP_EVERY_CHARS] characters and ends the work by throwing (a cancelled coroutine's check).
     */
    fun distinct(text: CharSequence, stop: () -> Unit = {}): IntArray {
        val seen = BooleanArray(BASIC_PLANE)
        val beyond = HashSet<Int>()
        var i = 0
        var nextStop = STOP_EVERY_CHARS
        while (i < text.length) {
            if (i >= nextStop) {
                stop()
                nextStop += STOP_EVERY_CHARS
            }
            val code = codePointAt(text, i, text.length)
            if (code < BASIC_PLANE) seen[code] = true else beyond += code
            i += if (code < BASIC_PLANE) 1 else 2
        }
        val basic = (0 until BASIC_PLANE).filter { seen[it] }
        return (basic + beyond.sorted()).toIntArray()
    }

    /**
     * [codes] less any character a layout would start a new line at — measured one per line in a single
     * layout, such a character would shift every width after it.
     */
    fun measurable(codes: IntArray): IntArray = codes.filter { it !in LINE_BREAKING }.toIntArray()

    /** [code] as text: one UTF-16 unit, or two. */
    fun text(code: Int): String =
        if (code < BASIC_PLANE) {
            code.toChar().toString()
        } else {
            val offset = code - 0x10000
            "" + (0xD800 + (offset shr 10)).toChar() + (0xDC00 + (offset and 0x3FF)).toChar()
        }

    /** LF, VT, FF, CR, NEL, and the line and paragraph separators. */
    private val LINE_BREAKING = setOf(0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)

    /** How many screen lines [text] from [start] until [end] wraps to in [widthPx]; an empty row is one line. */
    fun count(text: CharSequence, start: Int, end: Int, widthPx: Float, widths: LetterWidths): Int {
        var lines = 1
        var lineWidth = 0f
        var i = start
        while (i < end) {
            // Spaces only ever add to a line: they never start one, so spaces ending a line hang past it.
            if (text[i] == ' ') {
                lineWidth += widths.of(' '.code)
                i++
                continue
            }
            var j = i
            var word = 0f
            while (j < end && text[j] != ' ') {
                val code = codePointAt(text, j, end)
                word += widths.of(code)
                j += if (code < BASIC_PLANE) 1 else 2
            }
            if (lineWidth + word <= widthPx) {
                lineWidth += word
            } else {
                // A word that does not fit takes a fresh line, unless the line holds nothing yet; there it
                // fits whole, or — wider than a line — is broken between characters.
                if (lineWidth > 0f) {
                    lines++
                    lineWidth = 0f
                }
                var k = i
                while (k < j) {
                    val code = codePointAt(text, k, j)
                    val letter = widths.of(code)
                    if (lineWidth + letter > widthPx && lineWidth > 0f) {
                        lines++
                        lineWidth = 0f
                    }
                    lineWidth += letter
                    k += if (code < BASIC_PLANE) 1 else 2
                }
            }
            i = j
        }
        return lines
    }

    /**
     * Each of [rows]' heights in px at [widthPx]: [firstLinePx] for its first screen line and [nextLinePx]
     * for each one after.
     * The array starts with [leading] empty places (items above the rows, such as a note) so the column
     * takes it as it is, with no copy. [stop] as for [distinct], every [STOP_EVERY_CHARS] characters wrapped.
     */
    fun heights(
        rows: TextRows,
        widthPx: Float,
        widths: LetterWidths,
        firstLinePx: Int,
        nextLinePx: Int,
        leading: Int = 0,
        stop: () -> Unit = {},
    ): IntArray {
        val whole = rows.whole
        val heights = IntArray(leading + rows.count)
        var walked = 0
        for (row in 0 until rows.count) {
            val start = rows.start(row)
            val end = rows.end(row)
            heights[leading + row] = firstLinePx + (count(whole, start, end, widthPx, widths) - 1) * nextLinePx
            // A row holds at most ROW_MAX_CHARS characters, so the gap between checks stays bounded.
            walked += end - start
            if (walked >= STOP_EVERY_CHARS) {
                stop()
                walked = 0
            }
        }
        return heights
    }

    /** The code point at [i]: a surrogate pair read whole, any lone half as itself. */
    private fun codePointAt(text: CharSequence, i: Int, end: Int): Int {
        val high = text[i]
        if (high.isHighSurrogate() && i + 1 < end) {
            val low = text[i + 1]
            if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
        return high.code
    }
}

/** Code points below this are one UTF-16 unit. */
private const val BASIC_PLANE = 0x10000
