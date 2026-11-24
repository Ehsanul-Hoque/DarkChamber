package dev.ehsanfahad.darkchamber.feature.vault.presentation

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.impl.ListCompositeNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.entities.Entities
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A Markdown file as the viewer draws it: blocks holding styled runs of text. The viewer's own model,
 * so the screen never reads the parser's tree and nothing here can open a link or fetch an image — a
 * link is its address, kept for the confirmation to show; an image is its description.
 */
internal class MarkdownDocument(val blocks: List<MdBlock>) {
    /** Counts only: the blocks hold vault content. */
    override fun toString(): String = "MarkdownDocument(${blocks.size} blocks)"
}

internal sealed interface MdBlock {
    /** [anchor] is the `#section` name a link inside the document reaches it by. */
    data class Heading(val level: Int, val spans: List<MdSpan>, val anchor: String) : MdBlock

    data class Paragraph(val spans: List<MdSpan>) : MdBlock

    /** Fenced or indented code, exactly as written; [language] is the fence's word, if any. */
    data class Code(val code: String, val language: String?) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    /**
     * [start] is the first item's number for a numbered list, `null` for bullets. A [loose] list
     * (blank lines between its items) is drawn with space between them, as Markdown means it.
     */
    data class ListBlock(val start: Int?, val loose: Boolean, val items: List<MdListItem>) : MdBlock

    /** Each column's alignment, the header's cells, then the body's rows of cells. */
    data class Table(val alignments: List<CellAlign>, val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock

    data object Rule : MdBlock

    /** Shown exactly as written, in monospace: HTML blocks and footnote definitions. */
    data class Raw(val text: String) : MdBlock
}

/** [task] is `null` for a plain item, else whether its box is ticked. */
internal data class MdListItem(val task: Boolean?, val blocks: List<MdBlock>)

internal enum class CellAlign { Start, Center, End }

/**
 * A run of text in one style. [link] is the address exactly as the file gives it (after Markdown's own
 * escapes); an [image] run is the image's description, standing where the image would be.
 */
internal data class MdSpan(
    val text: String,
    val strong: Boolean = false,
    val emphasis: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val image: Boolean = false,
)

/** How long reading one file as Markdown may take before it is shown as plain text instead. */
internal val MARKDOWN_TIME_BUDGET: Duration = 10.seconds

/** The most quote and list markers one line may open with before the file is shown as plain text. */
internal const val MARKDOWN_MAX_NESTING = 32

/**
 * The most columns one line's opening — its indentation and markers together — may take (a tab
 * reaching the next multiple of four): how deep lists nest is told by indentation, not markers.
 */
internal const val MARKDOWN_MAX_INDENT = 128

/**
 * The most levels of styles, links and images one run of text may sit inside before the file is shown
 * as plain text. Reading them recurses per level, so a few kilobytes of nested `**` would otherwise run
 * out of stack; no file written by hand comes near it.
 */
internal const val MARKDOWN_MAX_INLINE_NESTING = 64

/**
 * The most columns a table is drawn with. A wider one — or one with a row holding more than
 * [TextReading.ROW_MAX_CHARS] characters — is shown as its source, as written: drawn, it would be one
 * row wider or taller than a layout may be, and cutting cells would hide part of the file.
 */
internal const val MARKDOWN_MAX_TABLE_COLUMNS = 64

internal object MarkdownModel {

    /**
     * Reads [text] as GitHub-flavoured Markdown, or answers `null` — "can't format": shown as plain
     * text with a note — when it nests deeper than the limits allow or reading it takes [budget].
     * Never throws on what a file holds: the parser runs with its assertions off, so input it cannot
     * make sense of comes back as plain paragraphs. Throws [CancellationException] as soon as
     * [cancelled] says so, so a lock or a swipe away stops the parse, or the reading of its tree, where
     * it is.
     *
     * Both the parser and this reading recurse per level, and a stack overflow cannot be caught on iOS.
     * Quotes and lists are limited before parsing, from each line's opening; styles inside a run of text
     * are limited as the tree is read ([MARKDOWN_MAX_INLINE_NESTING]) — the parser itself goes far
     * deeper than that without trouble, and keeps links and images shallow on its own. The budget, and
     * [cancelled], are checked by the parser as it goes and again at every piece of text as its tree is
     * read: the parse can take time that grows with the square of the input (long runs of brackets),
     * and a large file's reading takes long enough that a lock should not wait for it.
     */
    fun from(
        text: String,
        budget: Duration = MARKDOWN_TIME_BUDGET,
        cancelled: () -> Boolean = { false },
        clock: TimeSource = TimeSource.Monotonic,
    ): MarkdownDocument? {
        if (nestsTooDeep(text)) return null
        val started = clock.markNow()
        // Checked by the parser as it goes, and by the reading of its tree after: both can take long.
        val stop = {
            if (cancelled()) throw CancellationException("Markdown reading cancelled")
            if (started.elapsedNow() >= budget) throw CantFormat()
        }
        // The parser's String overload is deprecated in favour of this one; the text is the same.
        val chars: CharSequence = text
        return try {
            val tree = MarkdownParser(GFMFlavourDescriptor(), assertionsEnabled = false, cancellationToken = CancellationToken { stop() })
                .buildMarkdownTreeFromString(chars)
            MarkdownDocument(TreeReader(text, tree, stop).blocks(tree.children))
        } catch (_: CantFormat) {
            null
        }
    }

    /**
     * Whether any line opens with more than [MARKDOWN_MAX_NESTING] quote and list markers, or with an
     * opening wider than [MARKDOWN_MAX_INDENT] columns. Only a line's opening counts: `>` or `-` later
     * in a line nests nothing, and a dash not followed by a space is not a list item.
     */
    private fun nestsTooDeep(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            var markers = 0
            var columns = 0
            opening@ while (i < text.length) {
                val c = text[i]
                val next = text.getOrNull(i + 1)
                when {
                    c == ' ' -> { columns += 1; i += 1 }
                    c == '\t' -> { columns += 4 - columns % 4; i += 1 }
                    c == '>' -> { markers += 1; columns += 1; i += 1 }
                    (c == '-' || c == '*' || c == '+') && (next == ' ' || next == '\t') -> { markers += 1; columns += 1; i += 1 }
                    c in '0'..'9' -> {
                        var end = i
                        while (end < text.length && text[end] in '0'..'9') end += 1
                        val delimiter = text.getOrNull(end)
                        val after = text.getOrNull(end + 1)
                        if ((delimiter == '.' || delimiter == ')') && (after == ' ' || after == '\t')) {
                            markers += 1
                            columns += end + 1 - i
                            i = end + 1
                        } else {
                            break@opening
                        }
                    }
                    else -> break@opening
                }
            }
            if (markers > MARKDOWN_MAX_NESTING || columns > MARKDOWN_MAX_INDENT) return true
            while (i < text.length && text[i] != '\n' && text[i] != '\r') i += 1
            i += 1
        }
        return false
    }
}

/** A file past one of the reading limits — nesting or time: shown as plain text, never an error. */
private class CantFormat : RuntimeException()

/**
 * One parse tree read into blocks; holds what reading needs across the whole document. [check] throws to
 * stop the reading — a lock, a swipe, the time budget — and is called for every piece of a block's text,
 * where nearly all of a tree's nodes are; the rest of the reading is short and in step with the file.
 */
private class TreeReader(private val src: String, root: ASTNode, private val check: () -> Unit) {

    private val anchorsTaken = mutableMapOf<String, Int>()

    /**
     * Every link definition's address by its label — wherever it sits (a quote, a list), and the first
     * one for a label winning. Walked with a stack: nesting depth is the file's choice.
     */
    private val definitions: Map<String, String> = buildMap {
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
                val label = node.firstChild(MarkdownElementTypes.LINK_LABEL)?.let { labelKey(source(it)) }
                val address = node.firstChild(MarkdownElementTypes.LINK_DESTINATION)?.let(::address)
                if (label != null && address != null && label !in this) put(label, address)
            } else {
                // Reversed onto the stack so the document's first definition is met first.
                node.children.asReversed().forEach { pending.addLast(it) }
            }
        }
    }

    fun blocks(nodes: List<ASTNode>): List<MdBlock> = nodes.mapNotNull { block(it) }

    private fun block(node: ASTNode): MdBlock? =
        when (node.type) {
            MarkdownElementTypes.PARAGRAPH -> MdBlock.Paragraph(inline(node.children))
            in ATX_LEVELS -> heading(ATX_LEVELS.getValue(node.type), node.firstChild(MarkdownTokenTypes.ATX_CONTENT))
            MarkdownElementTypes.SETEXT_1 -> heading(1, node.firstChild(MarkdownTokenTypes.SETEXT_CONTENT))
            MarkdownElementTypes.SETEXT_2 -> heading(2, node.firstChild(MarkdownTokenTypes.SETEXT_CONTENT))
            MarkdownElementTypes.BLOCK_QUOTE -> MdBlock.Quote(blocks(node.children))
            MarkdownElementTypes.CODE_FENCE -> fencedCode(node)
            MarkdownElementTypes.CODE_BLOCK -> indentedCode(node)
            MarkdownTokenTypes.HORIZONTAL_RULE -> MdBlock.Rule
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> list(node)
            GFMElementTypes.TABLE -> table(node)
            MarkdownElementTypes.HTML_BLOCK -> MdBlock.Raw(source(node))
            // A definition is not shown — except a footnote's, which is read as written.
            MarkdownElementTypes.LINK_DEFINITION -> if (isFootnote(node)) MdBlock.Raw(source(node)) else null
            in LAYOUT_TOKENS -> null
            // Anything else the parser may produce is shown as written rather than lost.
            else -> MdBlock.Raw(source(node))
        }

    /** The lines between the fence's opening line and its closing one, exactly as written. */
    private fun fencedCode(node: ASTNode): MdBlock.Code {
        val language = node.firstChild(MarkdownTokenTypes.FENCE_LANG)?.let { unescape(source(it)).trim().substringBefore(' ') }
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var opened = false
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.CODE_FENCE_END -> break
                MarkdownTokenTypes.EOL -> if (opened) { lines += line.toString(); line.setLength(0) } else opened = true
                // Content comes only after the opening line's end, so it needs no guard of its own.
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> line.append(source(child))
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return MdBlock.Code(lines.joinToString("\n"), language?.ifEmpty { null })
    }

    /** Each line without the four columns of indent that made it code. */
    private fun indentedCode(node: ASTNode): MdBlock.Code {
        val text = StringBuilder()
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.CODE_LINE -> text.append(withoutIndent(source(child), INDENTED_CODE_COLUMNS))
                MarkdownTokenTypes.EOL -> text.append('\n')
            }
        }
        return MdBlock.Code(text.toString(), null)
    }

    /** [line] without up to [columns] columns of leading spaces; a tab reaches the next multiple of four. */
    private fun withoutIndent(line: String, columns: Int): String {
        var column = 0
        var i = 0
        while (i < line.length && column < columns) {
            when (line[i]) {
                ' ' -> column += 1
                '\t' -> column += 4 - column % 4
                else -> break
            }
            i += 1
        }
        return line.substring(i)
    }

    private fun list(node: ASTNode): MdBlock.ListBlock {
        val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val start = if (node.type == MarkdownElementTypes.ORDERED_LIST) {
            items.firstOrNull()?.firstChild(MarkdownTokenTypes.LIST_NUMBER)?.let { source(it).takeWhile(Char::isDigit).toIntOrNull() } ?: 1
        } else {
            null
        }
        val loose = (node as? ListCompositeNode)?.loose ?: false
        return MdBlock.ListBlock(start, loose, items.map { item ->
            val box = item.firstChild(GFMTokenTypes.CHECK_BOX)
            MdListItem(box?.let { source(it).any { c -> c == 'x' || c == 'X' } }, blocks(item.children))
        })
    }

    /**
     * Every row has the header's number of cells: a short one filled with empty cells, a long one cut —
     * or the table as written, when it is too wide or a row too long to draw.
     */
    private fun table(node: ASTNode): MdBlock {
        val header = node.firstChild(GFMElementTypes.HEADER)
        // Counted before any cell is read: a table can be a quarter of a million columns wide.
        if ((header?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0) > MARKDOWN_MAX_TABLE_COLUMNS) return MdBlock.Raw(source(node))
        val headerCells = header?.let(::cells) ?: emptyList()
        val delimiter = node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }?.let(::source) ?: ""
        val alignments = delimiter.split('|').map { it.trim() }.filter { it.isNotEmpty() }.map {
            when {
                it.startsWith(':') && it.endsWith(':') -> CellAlign.Center
                it.endsWith(':') -> CellAlign.End
                else -> CellAlign.Start
            }
        }
        val columns = headerCells.size
        val rows = node.children.filter { it.type == GFMElementTypes.ROW }.map { row ->
            val cells = cells(row)
            List(columns) { cells.getOrElse(it) { emptyList() } }
        }
        val tooLong = (listOf(headerCells) + rows).any { row -> row.sumOf { cell -> cell.sumOf { it.text.length } } > TextReading.ROW_MAX_CHARS }
        if (tooLong) return MdBlock.Raw(source(node))
        return MdBlock.Table(List(columns) { alignments.getOrElse(it) { CellAlign.Start } }, headerCells, rows)
    }

    private fun cells(row: ASTNode): List<List<MdSpan>> =
        row.children.filter { it.type == GFMTokenTypes.CELL }.map { inline(it.children) }

    private fun heading(level: Int, content: ASTNode?): MdBlock.Heading {
        val spans = content?.let { inline(it.children) } ?: emptyList()
        return MdBlock.Heading(level, spans, anchorFor(spans.joinToString("") { it.text }))
    }

    /**
     * The `#section` name a heading is reached by, as GitHub makes it: lower case, anything but
     * letters, digits, marks, `-` and `_` dropped, each space a hyphen; a repeat gets `-1`, `-2`, ….
     */
    private fun anchorFor(title: String): String {
        val base = buildString {
            for (c in title.lowercase()) {
                when {
                    c == ' ' -> append('-')
                    c.isLetterOrDigit() || c == '-' || c == '_' || c.category in MARKS -> append(c)
                }
            }
        }
        // GitHub's own loop: count up from the base until the name is free, and take that name too.
        var anchor = base
        while (anchor in anchorsTaken) {
            val count = anchorsTaken.getValue(base) + 1
            anchorsTaken[base] = count
            anchor = "$base-$count"
        }
        anchorsTaken[anchor] = 0
        return anchor
    }

    // --- Inline content ---

    private fun inline(nodes: List<ASTNode>): List<MdSpan> = Runs().also { inline(nodes, MdSpan(""), it, depth = 0) }.spans

    /** Appends [nodes] to [out] in [style] (a span whose text is unused), [depth] levels inside a block's text. */
    private fun inline(all: List<ASTNode>, style: MdSpan, out: Runs, depth: Int) {
        if (depth > MARKDOWN_MAX_INLINE_NESTING) throw CantFormat()
        val inner = depth + 1
        // A quote's `>` at the start of each continued line is layout; read the line as starting after it.
        val nodes = all.filter { it.type != MarkdownTokenTypes.BLOCK_QUOTE }
        for ((i, node) in nodes.withIndex()) {
            check()
            // The angle brackets around an email address belong to the autolink, not the text.
            if (node.type == MarkdownTokenTypes.LT && nodes.getOrNull(i + 1)?.type == MarkdownTokenTypes.EMAIL_AUTOLINK) continue
            if (node.type == MarkdownTokenTypes.GT && nodes.getOrNull(i - 1)?.type == MarkdownTokenTypes.EMAIL_AUTOLINK) continue
            when (node.type) {
                MarkdownTokenTypes.WHITE_SPACE ->
                    // Whitespace that starts or ends a line, or the whole run, is layout, not text.
                    if (i > 0 && i < nodes.lastIndex && nodes[i - 1].type != MarkdownTokenTypes.EOL && nodes[i + 1].type != MarkdownTokenTypes.EOL) {
                        out.add(source(node), style)
                    }
                MarkdownTokenTypes.EOL ->
                    // A soft break reads as a space; after a hard break the line has already ended.
                    if (i > 0 && i < nodes.lastIndex && nodes[i - 1].type != MarkdownTokenTypes.HARD_LINE_BREAK) out.add(" ", style)
                MarkdownTokenTypes.HARD_LINE_BREAK -> out.add("\n", style)
                MarkdownElementTypes.EMPH -> inline(node.children.trim(1), style.copy(emphasis = true), out, inner)
                MarkdownElementTypes.STRONG -> inline(node.children.trim(2), style.copy(strong = true), out, inner)
                GFMElementTypes.STRIKETHROUGH ->
                    inline(node.children.dropWhile { it.type == GFMTokenTypes.TILDE }.dropLastWhile { it.type == GFMTokenTypes.TILDE }, style.copy(strike = true), out, inner)
                MarkdownElementTypes.CODE_SPAN -> out.add(codeSpan(node), style.copy(code = true))
                MarkdownTokenTypes.HTML_TAG -> out.add(source(node), style)
                MarkdownElementTypes.INLINE_LINK -> {
                    val address = node.firstChild(MarkdownElementTypes.LINK_DESTINATION)?.let(::address) ?: ""
                    inline(linkText(node), style.copy(link = address), out, inner)
                }
                MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                    val address = referenceAddress(node)
                    // With no definition it is not a link at all: its brackets and words, as written.
                    if (address == null) inline(node.children, style, out, inner) else inline(linkText(node), style.copy(link = address), out, inner)
                }
                MarkdownElementTypes.AUTOLINK -> {
                    val target = node.firstChild(MarkdownTokenTypes.AUTOLINK)?.let(::source) ?: ""
                    out.add(target, style.copy(link = target))
                }
                GFMTokenTypes.GFM_AUTOLINK -> {
                    val target = source(node)
                    out.add(target, style.copy(link = if (target.startsWith("www.")) "http://$target" else target))
                }
                MarkdownTokenTypes.EMAIL_AUTOLINK -> out.add(source(node), style.copy(link = "mailto:${source(node)}"))
                MarkdownElementTypes.IMAGE -> image(node, style, out, inner)
                else ->
                    if (node.children.isEmpty()) out.add(unescape(source(node)), style) else inline(node.children, style, out, inner)
            }
        }
    }

    /** A link's words: its text in brackets, or for `[label]` and `[label][]`, the label's own words. */
    private fun linkText(link: ASTNode): List<ASTNode> {
        val text = link.firstChild(MarkdownElementTypes.LINK_TEXT) ?: link.firstChild(MarkdownElementTypes.LINK_LABEL)
        return text?.children?.trim(1) ?: emptyList()
    }

    /** A reference's address, or `null` when nothing defines its label or it is a footnote's. */
    private fun referenceAddress(link: ASTNode): String? {
        val label = link.firstChild(MarkdownElementTypes.LINK_LABEL)?.let(::source) ?: return null
        if (label.startsWith("[^")) return null
        return definitions[labelKey(label)]
    }

    /** CommonMark matches labels without regard to case or runs of whitespace. */
    private fun labelKey(label: String): String =
        label.removePrefix("[").removeSuffix("]").trim().lowercase().split(WHITESPACE_RUN).joinToString(" ")

    /** A destination as written, without its angle brackets, Markdown's escapes and references read. */
    private fun address(destination: ASTNode): String {
        val text = source(destination)
        val bare = if (text.length >= 2 && text.first() == '<' && text.last() == '>') text.substring(1, text.length - 1) else text
        return unescape(bare)
    }

    private fun isFootnote(definition: ASTNode): Boolean =
        definition.firstChild(MarkdownElementTypes.LINK_LABEL)?.let { source(it).startsWith("[^") } == true

    /**
     * An image stands as its description — the plain words of its brackets, styles dropped, as
     * CommonMark's `alt` is — never as anything fetched. It keeps a place even with no words.
     */
    private fun image(node: ASTNode, style: MdSpan, out: Runs, depth: Int) {
        val link = node.children.firstOrNull { it.type != MarkdownTokenTypes.EXCLAMATION_MARK }
        if (link == null || (link.type != MarkdownElementTypes.INLINE_LINK && referenceAddress(link) == null)) {
            // An image reference with no definition is its text as written.
            out.add(unescape(source(node)), style)
            return
        }
        // Inside an image's brackets the parser reads an inner image as `!` then a link; the `!` is its mark.
        val text = linkText(link)
        val words = text.filterIndexed { i, n -> !(n.type == MarkdownTokenTypes.EXCLAMATION_MARK && text.getOrNull(i + 1)?.type in LINKS) }
        out.addImage(Runs().also { inline(words, MdSpan(""), it, depth) }.spans.joinToString("") { it.text }, style)
    }

    /**
     * A code span's text: what lies between its backtick runs, line endings read as spaces, and one
     * space stripped from each end when both ends have one and it is not all spaces.
     */
    private fun codeSpan(node: ASTNode): String {
        val inner = node.children.dropWhile { it.type == MarkdownTokenTypes.BACKTICK }.dropLastWhile { it.type == MarkdownTokenTypes.BACKTICK }
        if (inner.isEmpty()) return ""
        val text = src.substring(inner.first().startOffset, inner.last().endOffset).replace('\n', ' ')
        return if (text.length >= 2 && text.first() == ' ' && text.last() == ' ' && text.isNotBlank()) text.substring(1, text.length - 1) else text
    }

    private fun source(node: ASTNode): String = src.substring(node.startOffset, node.endOffset)

    private fun ASTNode.firstChild(type: IElementType): ASTNode? = children.firstOrNull { it.type == type }

    /** Without [n] delimiter tokens at each end. */
    private fun List<ASTNode>.trim(n: Int): List<ASTNode> = if (size >= 2 * n) subList(n, size - n) else emptyList()

    private companion object {
        val ATX_LEVELS = mapOf(
            MarkdownElementTypes.ATX_1 to 1, MarkdownElementTypes.ATX_2 to 2, MarkdownElementTypes.ATX_3 to 3,
            MarkdownElementTypes.ATX_4 to 4, MarkdownElementTypes.ATX_5 to 5, MarkdownElementTypes.ATX_6 to 6,
        )
        val MARKS = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)

        /** Tokens that only shape a block — line ends, indents, a quote's `>`, a list's marker — and carry no text. */
        val LAYOUT_TOKENS = setOf(
            MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.BLOCK_QUOTE,
            MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER, GFMTokenTypes.CHECK_BOX,
        )

        const val INDENTED_CODE_COLUMNS = 4

        val WHITESPACE_RUN = Regex("\\s+")

        val LINKS = setOf(
            MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK,
        )
    }
}

/**
 * Collects runs of text, joining a run to the last when the style is the same. The open run's words grow
 * in a builder: copying the whole run on each addition would make a run of many small pieces (a line of
 * brackets is one piece each) cost the square of its length.
 */
private class Runs {
    private val closed = mutableListOf<MdSpan>()

    /** The open run's style (its text unused), or `null` when no run is open. */
    private var openStyle: MdSpan? = null
    private val openText = StringBuilder()

    val spans: List<MdSpan>
        get() {
            close()
            return closed
        }

    fun add(text: String, style: MdSpan) {
        if (text.isEmpty()) return
        val key = style.copy(text = "")
        if (key != openStyle) {
            close()
            openStyle = key
        }
        openText.append(text)
    }

    /** An image is its own run, never joined to text beside it — and kept even with no words. */
    fun addImage(description: String, style: MdSpan) {
        close()
        closed += style.copy(text = description, image = true)
    }

    private fun close() {
        val style = openStyle ?: return
        closed += style.copy(text = openText.toString())
        openText.setLength(0)
        openStyle = null
    }
}

/**
 * Markdown's backslash escapes and character references read as the characters they stand for, in
 * one pass from the left (so an escaped `&` never starts a reference). A reference to no character, or
 * to one that cannot be, reads as U+FFFD, as CommonMark says.
 */
internal fun unescape(text: String): String {
    if (text.none { it == BACKSLASH || it == '&' }) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == BACKSLASH && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
            out.append(text[i + 1])
            i += 2
        } else if (c == '&') {
            val reference = REFERENCE.matchAt(text, i)
            val codePoint = reference?.let { codePointOf(it) }
            if (reference != null && codePoint != null) {
                appendCodePoint(out, codePoint)
                i += reference.value.length
            } else {
                out.append(c)
                i += 1
            }
        } else {
            out.append(c)
            i += 1
        }
    }
    return out.toString()
}

private fun codePointOf(reference: MatchResult): Int? {
    val (name, decimal, hex) = reference.destructured
    return when {
        name.isNotEmpty() -> Entities.map["&$name;"]
        decimal.isNotEmpty() -> decimal.toInt()
        else -> hex.toInt(16)
    }
}

private fun appendCodePoint(out: StringBuilder, codePoint: Int) {
    when {
        codePoint == 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF -> out.append(Char(0xFFFD))
        codePoint < 0x10000 -> out.append(Char(codePoint))
        else -> {
            val v = codePoint - 0x10000
            out.append(Char(0xD800 + (v shr 10))).append(Char(0xDC00 + (v and 0x3FF)))
        }
    }
}

private val BACKSLASH = Char(0x5C)
/** ASCII punctuation: the only characters a backslash escapes (the backslash itself among them). */
private val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[]^_`{|}~" + BACKSLASH
private val REFERENCE = Regex("&(?:([a-zA-Z][a-zA-Z0-9]*)|#([0-9]{1,7})|#[xX]([0-9a-fA-F]{1,6}));")
