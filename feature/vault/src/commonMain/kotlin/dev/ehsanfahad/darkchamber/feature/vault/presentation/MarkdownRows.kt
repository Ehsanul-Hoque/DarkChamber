package dev.ehsanfahad.darkchamber.feature.vault.presentation

/** How large a run of words is drawn. */
internal enum class MdTextSize { Title1, Title2, Title3, Body }

/**
 * One item of a formatted Markdown page. A block becomes one row or several: a block's size follows the
 * file, and a layout may not be taller or wider than Compose allows, so no row holds more than about
 * [TextReading.ROW_MAX_CHARS] characters — a table's piece counting all its cells, and a table too wide
 * or with a row too long for that shown as written instead ([MARKDOWN_MAX_TABLE_COLUMNS]).
 *
 * Every row knows the top-level [block] it came from — where a `#section` link lands — how many list
 * levels it sits in ([indent]) and how many quotes ([quotes]), and whether space comes before it
 * ([gapAbove]): between blocks, and between the items of a loose list. A row prints counts and kinds,
 * never the file's words: one holding text of its own says how long it is, the others print their runs.
 */
internal sealed interface MdRow {
    val block: Int
    val indent: Int
    val quotes: Int
    val gapAbove: Boolean

    /**
     * Words: a heading, a paragraph, or a piece of a long one. [marker] starts a list item's first row
     * — a bullet, a number or a task's box — and [markerSpoken] is what a screen reader says for it.
     * [first] and [last] say whether a piece starts and ends its paragraph (both, when it is not cut), so
     * the lines either side of a cut sit as far apart as any two ([TextReading.spaced]).
     */
    data class Words(
        override val block: Int,
        override val indent: Int,
        override val quotes: Int,
        override val gapAbove: Boolean,
        val size: MdTextSize,
        val spans: List<MdSpan>,
        val marker: String? = null,
        val markerSpoken: String? = null,
        val first: Boolean = true,
        val last: Boolean = true,
    ) : MdRow

    /**
     * Code, or a piece of a long block of it, in monospace and unwrapped; [widthChars] is the longest
     * line of the whole block, so its pieces are one width and scroll sideways together — every piece
     * of one block has the same [group], and no other block has it.
     */
    data class Code(
        override val block: Int,
        override val indent: Int,
        override val quotes: Int,
        override val gapAbove: Boolean,
        val group: Int,
        val text: String,
        val widthChars: Int,
        val first: Boolean,
        val last: Boolean,
    ) : MdRow {
        override fun toString(): String = "Code(block=$block, group=$group, ${text.length} chars)"
    }

    /**
     * Rows of a table — the header, on the first piece only, and some of the body — with each column
     * [widths] characters wide across the whole table, so the pieces line up and scroll together (one
     * [group] per table, as for code).
     */
    data class TableRows(
        override val block: Int,
        override val indent: Int,
        override val quotes: Int,
        override val gapAbove: Boolean,
        val group: Int,
        val widths: List<Int>,
        val alignments: List<CellAlign>,
        val header: List<List<MdSpan>>?,
        val rows: List<List<List<MdSpan>>>,
    ) : MdRow

    data class Rule(
        override val block: Int,
        override val indent: Int,
        override val quotes: Int,
        override val gapAbove: Boolean,
    ) : MdRow

    /**
     * HTML and footnotes as written, in monospace and wrapped, or a piece of them; [first] and [last] as
     * for [Words].
     */
    data class Raw(
        override val block: Int,
        override val indent: Int,
        override val quotes: Int,
        override val gapAbove: Boolean,
        val text: String,
        val first: Boolean = true,
        val last: Boolean = true,
    ) : MdRow {
        override fun toString(): String = "Raw(block=$block, ${text.length} chars)"
    }
}

internal object MarkdownRows {
    /** The most body rows of a table in one row of the page; fewer when their cells are long. */
    const val TABLE_ROWS_PER_PIECE = 10

    /**
     * The most lines of code or raw text in one row of the page. Pieces stay shorter than a screen: the
     * thumb moves row by row, so a row taller than the screen would have parts it could not reach.
     */
    const val LINES_PER_PIECE = 20

    /** The widest a table column is drawn, in characters; a longer cell wraps inside it. */
    const val MAX_COLUMN_CHARS = 30

    /** The deepest indentation and the most quote bars drawn; deeper nesting is drawn at this depth. */
    const val MAX_DRAWN_DEPTH = 8

    /** The rows a document is drawn as, in order. */
    fun rows(
        document: MarkdownDocument,
        maxChars: Int = TextReading.ROW_MAX_CHARS,
    ): List<MdRow> {
        require(maxChars >= 2)
        val out = mutableListOf<MdRow>()
        val groups = Counter()
        document.blocks.forEachIndexed { index, block ->
            Builder(out, index, maxChars, groups).block(
                block,
                Place(indent = 0, quotes = 0, listDepth = 0),
                gap =
                    index > 0,
                marker = null,
            )
        }
        return out
    }

    /** The first row of top-level block [block]: where a `#section` link brings the page. */
    fun firstRowOf(
        rows: List<MdRow>,
        block: Int,
    ): Int? = rows.indexOfFirst { it.block == block }.takeIf { it >= 0 }

    /** The bullet for a list [depth] levels deep (0 outermost): a dot, a ring, a square, then round again. */
    fun bullet(depth: Int): String = BULLETS[depth % BULLETS.size]

    private val BULLETS = listOf("" + Char(0x2022), "" + Char(0x25E6), "" + Char(0x25AA))
    private val TICKED = "" + Char(0x2611)
    private val UNTICKED = "" + Char(0x2610)

    /** Where a block sits: list levels, quotes, and how many lists deep for its bullets. */
    private data class Place(
        val indent: Int,
        val quotes: Int,
        val listDepth: Int,
    )

    /** A list item's mark, and what a screen reader says for it (`null`: nothing of its own). */
    private class Marker(
        val shown: String,
        val spoken: String?,
    )

    /** Hands out each code block and table its own group, across the whole document. */
    private class Counter {
        private var next = 0

        fun take(): Int = next++
    }

    private class Builder(
        private val out: MutableList<MdRow>,
        private val top: Int,
        private val maxChars: Int,
        private val groups: Counter,
    ) {
        fun block(
            block: MdBlock,
            at: Place,
            gap: Boolean,
            marker: Marker?,
        ) {
            when (block) {
                is MdBlock.Heading -> {
                    words(sizeOf(block.level), block.spans, at, gap, marker)
                }

                is MdBlock.Paragraph -> {
                    words(MdTextSize.Body, block.spans, at, gap, marker)
                }

                is MdBlock.Code -> {
                    pieces(block.code, at, gap, marker, code = true)
                }

                is MdBlock.Raw -> {
                    pieces(block.text, at, gap, marker, code = false)
                }

                is MdBlock.Table -> {
                    table(block, at, markerRow(at, gap, marker) ?: gap)
                }

                MdBlock.Rule -> {
                    val gapHere = markerRow(at, gap, marker) ?: gap
                    out += MdRow.Rule(top, at.indent, at.quotes, gapHere)
                }

                is MdBlock.Quote -> {
                    val inner = at.copy(quotes = at.quotes + 1)
                    block.blocks.forEachIndexed { i, child ->
                        block(
                            child,
                            inner,
                            if (i ==
                                0
                            ) {
                                gap
                            } else {
                                true
                            },
                            if (i == 0) marker else null,
                        )
                    }
                }

                is MdBlock.ListBlock -> {
                    list(block, at, gap, marker)
                }
            }
        }

        private fun list(
            list: MdBlock.ListBlock,
            at: Place,
            gap: Boolean,
            outer: Marker?,
        ) {
            // A list that opens an item: the item's mark gets a row, then the list starts under it.
            var gapNext = markerRow(at, gap, outer) ?: gap
            val inner = Place(indent = at.indent + 1, quotes = at.quotes, listDepth = at.listDepth + 1)
            list.items.forEachIndexed { k, item ->
                val itemGap = if (k == 0) gapNext else list.loose
                val marker =
                    when {
                        item.task != null -> {
                            Marker(
                                if (item.task) TICKED else UNTICKED,
                                ViewerFormat.taskSpoken(item.task),
                            )
                        }

                        list.start != null -> {
                            "${list.start + k}.".let { Marker(it, it) }
                        }

                        else -> {
                            Marker(bullet(at.listDepth), null)
                        }
                    }
                if (item.blocks.isEmpty()) {
                    out +=
                        MdRow.Words(
                            top,
                            inner.indent,
                            inner.quotes,
                            itemGap,
                            MdTextSize.Body,
                            emptyList(),
                            marker.shown,
                            marker.spoken,
                        )
                }
                item.blocks.forEachIndexed { i, child ->
                    block(
                        child,
                        inner,
                        if (i ==
                            0
                        ) {
                            itemGap
                        } else {
                            true
                        },
                        if (i == 0) marker else null,
                    )
                }
                gapNext = false
            }
        }

        /** A row holding only a list item's mark, for an item opening with something other than words. */
        private fun markerRow(
            at: Place,
            gap: Boolean,
            marker: Marker?,
        ): Boolean? {
            if (marker == null) return null
            out +=
                MdRow.Words(top, at.indent, at.quotes, gap, MdTextSize.Body, emptyList(), marker.shown, marker.spoken)
            return false
        }

        private fun words(
            size: MdTextSize,
            spans: List<MdSpan>,
            at: Place,
            gap: Boolean,
            marker: Marker?,
        ) {
            val pieces = cutSpans(spans)
            pieces.forEachIndexed { i, piece ->
                out +=
                    MdRow.Words(
                        block = top,
                        indent = at.indent,
                        quotes = at.quotes,
                        gapAbove = i == 0 && gap,
                        size = size,
                        spans = piece,
                        marker = if (i == 0) marker?.shown else null,
                        markerSpoken = if (i == 0) marker?.spoken else null,
                        first = i == 0,
                        last = i == pieces.lastIndex,
                    )
            }
        }

        /** [spans] in pieces of at most [maxChars] characters, cut where a plain line would be. */
        private fun cutSpans(spans: List<MdSpan>): List<List<MdSpan>> {
            val whole = spans.joinToString("") { it.text }
            val cuts = mutableListOf(0)
            while (whole.length - cuts.last() > maxChars) cuts += TextReading.cutAt(whole, cuts.last(), maxChars)
            cuts += whole.length
            return cuts.zipWithNext { from, to -> slice(spans, from, to) }
        }

        private fun slice(
            spans: List<MdSpan>,
            from: Int,
            to: Int,
        ): List<MdSpan> {
            val piece = mutableListOf<MdSpan>()
            var offset = 0
            for (span in spans) {
                val start = maxOf(from, offset)
                val end = minOf(to, offset + span.text.length)
                if (start < end) piece += span.copy(text = span.text.substring(start - offset, end - offset))
                offset += span.text.length
            }
            return piece
        }

        /** Code or raw text as pieces of whole (or cut) lines, each at most [maxChars] characters. */
        private fun pieces(
            text: String,
            at: Place,
            gap: Boolean,
            marker: Marker?,
            code: Boolean,
        ) {
            val gapHere = markerRow(at, gap, marker) ?: gap
            val rows = TextReading.rows(TextDecoding.lines(text), maxChars)
            val lines = List(rows.count) { rows.row(it) }
            val width = lines.maxOfOrNull { it.length } ?: 0
            val groups = mutableListOf<MutableList<String>>()
            var size = 0
            for (line in lines) {
                val last = groups.lastOrNull()
                if (last != null && last.size < LINES_PER_PIECE && size + 1 + line.length <= maxChars) {
                    last += line
                    size += 1 + line.length
                } else {
                    groups += mutableListOf(line)
                    size = line.length
                }
            }
            if (groups.isEmpty()) groups += mutableListOf("")
            val group = this.groups.take()
            groups.forEachIndexed { i, lines ->
                val joined = lines.joinToString("\n")
                val gapAbove = i == 0 && gapHere
                out +=
                    if (code) {
                        MdRow.Code(
                            top,
                            at.indent,
                            at.quotes,
                            gapAbove,
                            group,
                            joined,
                            width,
                            first = i == 0,
                            last =
                                i == groups.lastIndex,
                        )
                    } else {
                        MdRow.Raw(
                            top,
                            at.indent,
                            at.quotes,
                            gapAbove,
                            joined,
                            first = i == 0,
                            last =
                                i == groups.lastIndex,
                        )
                    }
            }
        }

        private fun table(
            table: MdBlock.Table,
            at: Place,
            gap: Boolean,
        ) {
            val columns = table.alignments.size
            val all = listOf(table.header) + table.rows
            val widths =
                List(columns) { c ->
                    all.maxOf { row -> row.getOrNull(c)?.sumOf { it.text.length } ?: 0 }.coerceIn(1, MAX_COLUMN_CHARS)
                }
            val pieces = tablePieces(table)
            val group = groups.take()
            pieces.forEachIndexed { i, rows ->
                out +=
                    MdRow.TableRows(
                        block = top,
                        indent = at.indent,
                        quotes = at.quotes,
                        gapAbove = i == 0 && gap,
                        group = group,
                        widths = widths,
                        alignments = table.alignments,
                        header = if (i == 0) table.header else null,
                        rows = rows,
                    )
            }
        }

        /**
         * A table's body rows in pieces of at most [TABLE_ROWS_PER_PIECE] rows whose cells together — the
         * header's too, in the first — stay within [maxChars]: cells wrap in their narrow columns, so
         * characters are what make a piece tall. A row past that on its own still gets a piece, and a
         * header too long to share one has the first piece to itself.
         */
        private fun tablePieces(table: MdBlock.Table): List<List<List<List<MdSpan>>>> {
            val pieces = mutableListOf<MutableList<List<List<MdSpan>>>>(mutableListOf())
            var chars = charsIn(table.header)
            for (row in table.rows) {
                val size = charsIn(row)
                val piece = pieces.last()
                // A piece holding something — rows, or the header's words — is closed by what would overfill it.
                val holds = piece.isNotEmpty() || chars > 0
                if (holds && (piece.size == TABLE_ROWS_PER_PIECE || chars + size > maxChars)) {
                    pieces += mutableListOf(row)
                    chars = size
                } else {
                    piece += row
                    chars += size
                }
            }
            return pieces
        }

        private fun charsIn(row: List<List<MdSpan>>): Int = row.sumOf { cell -> cell.sumOf { it.text.length } }

        private fun sizeOf(level: Int): MdTextSize =
            when (level) {
                1 -> MdTextSize.Title1
                2 -> MdTextSize.Title2
                else -> MdTextSize.Title3
            }
    }
}
