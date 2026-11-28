package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Markdown into the viewer's own model. Inputs are the CommonMark 0.31.2 specification's examples (cited
 * by number) and GitHub's table, task-list and strikethrough examples, copied rather than written to fit
 * the code; each expected model is read off the specification's own HTML for that example.
 */
class MarkdownModelTest {
    /** A backslash, built rather than typed, so no escape sequence is ever written into this file. */
    private val bs = Char(0x5C)

    private fun blocks(markdown: String) = assertNotNull(MarkdownModel.from(markdown), "formatted").blocks

    private fun para(vararg spans: MdSpan) = MdBlock.Paragraph(spans.toList())

    private fun plain(text: String) = MdSpan(text)

    /** A heading's level and runs; its anchor has tests of its own. */
    private fun headings(markdown: String) =
        blocks(markdown).map { (it as MdBlock.Heading).let { h -> h.level to h.spans } }

    // --- Headings and paragraphs ---

    @Test
    fun atx_headings_one_to_six() { // Example 62
        assertEquals(
            (1..6).map { it to listOf(plain("foo")) },
            headings("# foo\n## foo\n### foo\n#### foo\n##### foo\n###### foo\n"),
        )
    }

    @Test
    fun seven_hashes_are_a_paragraph() { // Example 63
        assertEquals(listOf(para(plain("####### foo"))), blocks("####### foo\n"))
    }

    @Test
    fun setext_headings_carry_inline_styles() { // Example 80
        assertEquals(
            listOf(
                1 to listOf(plain("Foo "), MdSpan("bar", emphasis = true)),
                2 to listOf(plain("Foo "), MdSpan("bar", emphasis = true)),
            ),
            headings("Foo *bar*\n=========\n\nFoo *bar*\n---------\n"),
        )
    }

    /** A line break inside a paragraph reads as a space, as a browser shows it. */
    @Test
    fun a_soft_line_break_reads_as_a_space() { // Example 220
        assertEquals(listOf(para(plain("aaa bbb")), para(plain("ccc ddd"))), blocks("aaa\nbbb\n\nccc\nddd\n"))
    }

    /**
     * A known difference from CommonMark, which makes a heading of this (example 81): the parser
     * reads a heading whose text runs over two lines as a paragraph ending in its underline. Pinned so
     * a parser update that changes it is noticed; nothing is lost, only the heading's size.
     */
    @Test
    fun a_two_line_setext_heading_reads_as_a_paragraph() {
        assertEquals(
            listOf(para(plain("Foo "), MdSpan("bar baz", emphasis = true), plain(" ===="))),
            blocks("Foo *bar\nbaz*\n====\n"),
        )
    }

    @Test
    fun blank_lines_only_separate() { // Example 227
        assertEquals(
            listOf(para(plain("aaa")), MdBlock.Heading(1, listOf(plain("aaa")), "aaa")),
            blocks("  \n\naaa\n  \n\n# aaa\n\n  \n"),
        )
    }

    /** Two trailing spaces or a backslash end a line; a backslash at the paragraph's end is just a backslash. */
    @Test
    fun hard_line_breaks() { // Examples 633, 634, 644
        assertEquals(listOf(para(plain("foo\nbaz"))), blocks("foo  \nbaz\n"))
        assertEquals(listOf(para(plain("foo\nbaz"))), blocks("foo$bs\nbaz\n"))
        assertEquals(listOf(para(plain("foo$bs"))), blocks("foo$bs\n"))
    }

    // --- Inline styles ---

    @Test
    fun emphasis_and_where_it_is_not() { // Examples 350, 360, 370
        assertEquals(listOf(para(MdSpan("foo bar", emphasis = true))), blocks("*foo bar*\n"))
        assertEquals(listOf(para(plain("foo_bar_"))), blocks("foo_bar_\n"))
        assertEquals(listOf(para(MdSpan("foo", emphasis = true), plain("bar"))), blocks("*foo*bar\n"))
    }

    @Test
    fun strong_and_emphasis_nest() {
        assertEquals(
            listOf(
                para(
                    plain("a "),
                    MdSpan("strong ", strong = true),
                    MdSpan("both", strong = true, emphasis = true),
                    plain(" b"),
                ),
            ),
            blocks("a **strong *both*** b\n"),
        )
    }

    @Test
    fun strikethrough() { // GitHub's strikethrough example
        assertEquals(
            listOf(para(MdSpan("Hi", strike = true), plain(" Hello, world!"))),
            blocks("~~Hi~~ Hello, world!\n"),
        )
    }

    @Test
    fun code_spans_keep_their_content() { // Examples 328, 329, 335, 336
        assertEquals(listOf(para(MdSpan("foo", code = true))), blocks("`foo`\n"))
        assertEquals(listOf(para(MdSpan("foo ` bar", code = true))), blocks("`` foo ` bar ``\n"))
        assertEquals(listOf(para(MdSpan("foo bar   baz", code = true))), blocks("``\nfoo\nbar  \nbaz\n``\n"))
        assertEquals(listOf(para(MdSpan("foo ", code = true))), blocks("``\nfoo \n``\n"))
    }

    /** Only one space is stripped from each side, and only when there is text between. */
    @Test
    fun a_code_span_of_spaces_keeps_them() {
        assertEquals(listOf(para(plain("a "), MdSpan("  ", code = true), plain(" b"))), blocks("a `  ` b\n"))
        assertEquals(listOf(para(MdSpan(" x", code = true))), blocks("`  x `\n"))
    }

    /** A backslash in a code span is itself, so it cannot escape the closing backtick. */
    @Test
    fun a_backslash_ends_nothing_in_a_code_span() { // Example 338
        assertEquals(listOf(para(MdSpan("foo$bs", code = true), plain("bar`"))), blocks("`foo$bs`bar`\n"))
    }

    @Test
    fun a_code_span_is_not_styled_inside() {
        assertEquals(listOf(para(MdSpan("*a* $bs*", code = true))), blocks("`*a* $bs*`\n"))
    }

    // --- Escapes and entities ---

    @Test
    fun backslash_escapes_show_the_character() { // Examples 12, 14
        val punctuation = "!\"#$%&'()*+,-./:;<=>?@[$bs]^_`{|}~"
        assertEquals(listOf(para(plain(punctuation))), blocks(punctuation.map { "$bs$it" }.joinToString("") + "\n"))
        assertEquals(
            listOf(para(plain("*not emphasized* [not a link](/foo) `not code`"))),
            blocks("$bs*not emphasized*\n$bs[not a link](/foo)\n$bs`not code`\n"),
        )
    }

    /** Only punctuation can be escaped; before anything else a backslash is itself. */
    @Test
    fun a_backslash_before_anything_else_is_kept() { // Example 13, without its tab and non-ASCII letters
        assertEquals(listOf(para(plain("${bs}A${bs}a$bs 3"))), blocks("${bs}A${bs}a$bs 3\n"))
    }

    @Test
    fun entities_show_their_character() { // Examples 25, 26
        val nbsp = Char(0xA0)
        assertEquals(listOf(para(plain("$nbsp & © Æ Ď"))), blocks("&nbsp; &amp; &copy; &AElig; &Dcaron;\n"))
        val replacement = Char(0xFFFD)
        assertEquals(
            listOf(para(plain("# " + Char(1234) + " " + Char(992) + " " + replacement))),
            blocks("&#35; &#1234; &#992; &#0;\n"),
        )
        assertEquals(
            listOf(para(plain(Char(0xD83D).toString() + Char(0xDE00)))),
            blocks("&#x1F600;\n"),
            "outside the basic plane",
        )
    }

    /** What only looks like a reference stays as written. */
    @Test
    fun a_reference_to_nothing_is_text() { // Examples 28, 29
        assertEquals(
            listOf(para(plain("&nbsp &x; &#; &#x; &#87654321; &#abcdef0; &ThisIsNotDefined; &hi?;"))),
            blocks("&nbsp &x; &#; &#x;\n&#87654321;\n&#abcdef0;\n&ThisIsNotDefined; &hi?;\n"),
        )
        assertEquals(listOf(para(plain("&copy"))), blocks("&copy\n"))
    }

    /** A number naming no character — past the last, or half of a surrogate pair — reads as U+FFFD. */
    @Test
    fun a_reference_to_an_impossible_character_reads_as_a_replacement() {
        val replacement = Char(0xFFFD).toString()
        assertEquals(
            listOf(para(plain("$replacement $replacement $replacement"))),
            blocks("&#x110000; &#xD800; &#57343;\n"),
        )
        assertEquals(
            listOf(para(plain(Char(0xDBFF).toString() + Char(0xDFFF)))),
            blocks("&#x10FFFF;\n"),
            "the last character there is",
        )
    }

    @Test
    fun an_escaped_ampersand_is_not_an_entity() { // Example 14, last line
        assertEquals(listOf(para(plain("&ouml; not a character entity"))), blocks("$bs&ouml; not a character entity\n"))
    }

    // --- Block structure ---

    @Test
    fun a_block_quote_holds_blocks_and_takes_a_lazy_line() { // Examples 228, 229, 232
        val quote =
            listOf(MdBlock.Quote(listOf(MdBlock.Heading(1, listOf(plain("Foo")), "foo"), para(plain("bar baz")))))
        assertEquals(quote, blocks("> # Foo\n> bar\n> baz\n"))
        assertEquals(quote, blocks("># Foo\n>bar\n> baz\n"))
        assertEquals(quote, blocks("> # Foo\n> bar\nbaz\n"), "the lazy line stays in the quote")
    }

    @Test
    fun block_quotes_nest() {
        assertEquals(
            listOf(MdBlock.Quote(listOf(para(plain("foo")), MdBlock.Quote(listOf(para(plain("bar"))))))),
            blocks("> foo\n>\n> > bar\n"),
        )
    }

    @Test
    fun fenced_code_keeps_every_character_and_its_language() { // Examples 142, 119, 147
        assertEquals(
            listOf(MdBlock.Code("def foo(x)\n  return 3\nend", "ruby")),
            blocks("```ruby\ndef foo(x)\n  return 3\nend\n```\n"),
        )
        assertEquals(listOf(MdBlock.Code("<\n >", null)), blocks("```\n<\n >\n```\n"))
        assertEquals(listOf(MdBlock.Code("``` aaa", null)), blocks("```\n``` aaa\n```\n"))
    }

    /** The language is the info string's first word; the rest of it, and an empty one, say nothing. */
    @Test
    fun a_fences_language_is_its_first_word() { // Example 143
        assertEquals(
            listOf(MdBlock.Code("def foo(x)\n  return 3\nend", "ruby")),
            blocks("~~~~    ruby startline=3 $%@#$\ndef foo(x)\n  return 3\nend\n~~~~~~~\n"),
        )
        assertEquals(listOf(MdBlock.Code("x", null)), blocks("```   \nx\n```\n"))
    }

    @Test
    fun fenced_code_keeps_blank_lines_markup_and_escapes_untouched() {
        assertEquals(listOf(MdBlock.Code("a\n\n\n*b* $bs* &amp;", null)), blocks("```\na\n\n\n*b* $bs* &amp;\n```\n"))
    }

    @Test
    fun indented_code_loses_only_its_indent() { // Examples 107, 115
        assertEquals(
            listOf(MdBlock.Code("a simple\n  indented code block", null)),
            blocks("    a simple\n      indented code block\n"),
        )
        assertEquals(
            listOf(MdBlock.Heading(1, listOf(plain("Heading")), "heading"), MdBlock.Code("foo", null)),
            blocks("# Heading\n    foo\n"),
        )
    }

    /** A tab reaches the next multiple of four columns; tabs after the indent are content. */
    @Test
    fun a_tab_indents_code_as_far_as_four_spaces() { // Examples 1, 2
        assertEquals(listOf(MdBlock.Code("foo\tbaz\t\tbim", null)), blocks("\tfoo\tbaz\t\tbim\n"))
        assertEquals(listOf(MdBlock.Code("foo\tbaz\t\tbim", null)), blocks("  \tfoo\tbaz\t\tbim\n"))
        assertEquals(listOf(MdBlock.Code("  foo", null)), blocks("\t  foo\n"), "the tab alone is the indent")
    }

    @Test
    fun code_inside_a_quote() { // Example 128
        assertEquals(
            listOf(MdBlock.Quote(listOf(MdBlock.Code("aaa", null))), para(plain("bbb"))),
            blocks("> ```\n> aaa\n\nbbb\n"),
        )
    }

    @Test
    fun thematic_breaks() { // Examples 43, 44, 51
        assertEquals(listOf(MdBlock.Rule, MdBlock.Rule, MdBlock.Rule), blocks("***\n---\n___\n"))
        assertEquals(listOf(para(plain("+++"))), blocks("+++\n"))
        assertEquals(listOf(MdBlock.Rule), blocks(" - - -\n"))
    }

    private fun item(
        vararg blocks: MdBlock,
        task: Boolean? = null,
    ) = MdListItem(task, blocks.toList())

    private fun itemText(
        text: String,
        task: Boolean? = null,
    ) = item(para(plain(text)), task = task)

    @Test
    fun a_change_of_bullet_starts_a_new_list() { // Example 301
        assertEquals(
            listOf(
                MdBlock.ListBlock(null, false, listOf(itemText("foo"), itemText("bar"))),
                MdBlock.ListBlock(null, false, listOf(itemText("baz"))),
            ),
            blocks("- foo\n- bar\n+ baz\n"),
        )
    }

    @Test
    fun numbered_lists_keep_their_first_number() { // Example 302
        assertEquals(
            listOf(
                MdBlock.ListBlock(1, false, listOf(itemText("foo"), itemText("bar"))),
                MdBlock.ListBlock(3, false, listOf(itemText("baz"))),
            ),
            blocks("1. foo\n2. bar\n3) baz\n"),
        )
    }

    @Test
    fun a_first_number_of_any_size_up_to_nine_digits() { // Examples 265, 266
        assertEquals(listOf(MdBlock.ListBlock(123456789, false, listOf(itemText("ok")))), blocks("123456789. ok\n"))
        assertEquals(listOf(para(plain("1234567890. not ok"))), blocks("1234567890. not ok\n"))
        assertEquals(listOf(MdBlock.ListBlock(0, false, listOf(itemText("ok")))), blocks("0. ok\n"))
        assertEquals(listOf(MdBlock.ListBlock(3, false, listOf(itemText("ok")))), blocks("003. ok\n"))
    }

    @Test
    fun blank_lines_between_items_make_a_loose_list() { // Example 306
        assertEquals(
            listOf(MdBlock.ListBlock(null, true, listOf(itemText("foo"), itemText("bar"), itemText("baz")))),
            blocks("- foo\n\n- bar\n\n\n- baz\n"),
        )
    }

    @Test
    fun lists_nest_and_each_keeps_its_own_looseness() { // Example 307
        val innermost = MdBlock.ListBlock(null, true, listOf(item(para(plain("baz")), para(plain("bim")))))
        val middle = MdBlock.ListBlock(null, false, listOf(item(para(plain("bar")), innermost)))
        assertEquals(
            listOf(MdBlock.ListBlock(null, false, listOf(item(para(plain("foo")), middle)))),
            blocks("- foo\n  - bar\n    - baz\n\n\n      bim\n"),
        )
    }

    @Test
    fun an_empty_item_is_kept() { // Example 281
        assertEquals(
            listOf(MdBlock.ListBlock(null, false, listOf(itemText("foo"), item(), itemText("bar")))),
            blocks("- foo\n-\n- bar\n"),
        )
    }

    @Test
    fun a_list_item_holds_any_blocks() { // Example 254
        assertEquals(
            listOf(
                MdBlock.ListBlock(
                    1,
                    true,
                    listOf(
                        item(
                            para(plain("A paragraph with two lines.")),
                            MdBlock.Code("indented code", null),
                            MdBlock.Quote(listOf(para(plain("A block quote.")))),
                        ),
                    ),
                ),
            ),
            blocks("1.  A paragraph\n    with two lines.\n\n        indented code\n\n    > A block quote.\n"),
        )
    }

    @Test
    fun task_items_carry_their_box() { // GitHub's task list example
        assertEquals(
            listOf(MdBlock.ListBlock(null, false, listOf(itemText("foo", task = false), itemText("bar", task = true)))),
            blocks("- [ ] foo\n- [x] bar\n"),
        )
        assertEquals(
            listOf(MdBlock.ListBlock(null, false, listOf(itemText("bar", task = true)))),
            blocks("- [X] bar\n"),
            "either case",
        )
    }

    private fun cells(vararg texts: String) = texts.map { if (it.isEmpty()) emptyList() else listOf(plain(it)) }

    @Test
    fun a_table_has_its_header_and_rows() { // GitHub's table examples
        assertEquals(
            listOf(
                MdBlock.Table(
                    listOf(CellAlign.Start, CellAlign.Start),
                    cells("foo", "bar"),
                    listOf(cells("baz", "bim")),
                ),
            ),
            blocks("| foo | bar |\n| --- | --- |\n| baz | bim |"),
        )
    }

    @Test
    fun a_tables_columns_keep_their_alignment() {
        assertEquals(
            listOf(
                MdBlock.Table(
                    listOf(CellAlign.Center, CellAlign.End),
                    cells("abc", "defghi"),
                    listOf(cells("bar", "baz")),
                ),
            ),
            blocks("| abc | defghi |\n:-: | -----------:\nbar | baz"),
        )
        assertEquals(listOf(CellAlign.Start), (blocks("| a |\n| :-- |\n| b |").single() as MdBlock.Table).alignments)
    }

    /** A short row is filled with empty cells; extra cells are dropped, as GitHub shows them. */
    @Test
    fun every_row_has_the_headers_number_of_cells() {
        assertEquals(
            listOf(
                MdBlock.Table(
                    listOf(CellAlign.Start, CellAlign.Start),
                    cells("abc", "def"),
                    listOf(cells("bar", ""), cells("bar", "baz")),
                ),
            ),
            blocks("| abc | def |\n| --- | --- |\n| bar |\n| bar | baz | boo |"),
        )
    }

    /** A table of [columns] one-letter columns, with one body row. */
    private fun table(columns: Int) =
        "a|".repeat(columns - 1) + "a\n" + "-|".repeat(columns - 1) + "-\n" + "b|".repeat(columns - 1) + "b\n"

    /**
     * A table wider than can be drawn is shown as written, every character of it, rather than as one
     * row wider than a layout may be. Both sides of the limit.
     */
    @Test
    fun a_table_too_wide_to_draw_is_shown_as_written() {
        val widest = blocks(table(MARKDOWN_MAX_TABLE_COLUMNS)).single()
        assertEquals(MARKDOWN_MAX_TABLE_COLUMNS, (widest as MdBlock.Table).alignments.size)
        val tooWide = table(MARKDOWN_MAX_TABLE_COLUMNS + 1)
        assertEquals(listOf(MdBlock.Raw(tooWide.trimEnd())), blocks(tooWide))
    }

    /**
     * Twenty thousand columns: shown as written. (A 1 MB table of a quarter of a million columns can take
     * the parser itself past the time budget on a slow device — then it is "can't format", also safe.)
     */
    @Test
    fun a_table_of_twenty_thousand_columns_is_shown_as_written() {
        assertIs<MdBlock.Raw>(blocks(table(20_001)).single())
    }

    /** A row holding more than a row of the page may: the table as written, not a cell cut short. */
    @Test
    fun a_table_with_a_row_too_long_to_draw_is_shown_as_written() {
        val fits = "| a | b |\n| - | - |\n| ${"x".repeat(1_000)} | ${"y".repeat(1_000)} |\n"
        assertIs<MdBlock.Table>(blocks(fits).single())
        val tooLong = "| a | b |\n| - | - |\n| ${"x".repeat(1_000)} | ${"y".repeat(1_001)} |\n"
        assertEquals(listOf(MdBlock.Raw(tooLong.trimEnd())), blocks(tooLong))
        val longHeader = "| ${"h".repeat(2_001)} |\n| - |\n| b |\n"
        assertIs<MdBlock.Raw>(blocks(longHeader).single(), "the header counts too")
    }

    @Test
    fun the_table_limits_are_sixty_four_columns_and_a_rows_length() {
        assertEquals(64, MARKDOWN_MAX_TABLE_COLUMNS)
        assertEquals(2_000, TextReading.ROW_MAX_CHARS)
    }

    @Test
    fun a_table_cell_holds_inline_styles_and_an_escaped_pipe() {
        assertEquals(
            listOf(
                MdBlock.Table(
                    listOf(CellAlign.Start),
                    listOf(listOf(plain("f|oo"))),
                    listOf(listOf(listOf(plain("b "), MdSpan("|", strong = true), plain(" im")))),
                ),
            ),
            blocks("| f$bs|oo  |\n| ------ |\n| b **$bs|** im |"),
        )
    }

    // --- Heading anchors, as GitHub makes them ---

    private fun anchors(markdown: String) = blocks(markdown).filterIsInstance<MdBlock.Heading>().map { it.anchor }

    @Test
    fun an_anchor_is_the_title_in_lower_case_with_hyphens_for_spaces() {
        assertEquals(listOf("foo-bar", "a--b", "a-b_c"), anchors("# Foo Bar\n# a  b\n# a-b_c\n"))
    }

    @Test
    fun punctuation_and_symbols_are_dropped_from_an_anchor() {
        val face = Char(0xD83D).toString() + Char(0xDE00)
        assertEquals(listOf("whats-new-v20", "hi-"), anchors("## What's new? (v2.0)\n# Hi $face\n"))
    }

    /** Other scripts keep their letters and their vowel signs, which are marks, not letters. */
    @Test
    fun an_anchor_keeps_any_scripts_letters_and_marks() {
        assertEquals(listOf("বাংলা-লেখা"), anchors("# বাংলা লেখা\n"))
    }

    @Test
    fun an_anchor_is_made_from_the_words_not_the_markup() {
        assertEquals(listOf("em-and-code"), anchors("# *Em* and `code`\n"))
    }

    @Test
    fun repeated_titles_are_numbered_and_never_collide() {
        assertEquals(listOf("a", "a-1", "a-2"), anchors("# a\n# a\n# a\n"))
        assertEquals(
            listOf("a-1", "a", "a-2"),
            anchors("# a-1\n# a\n# a\n"),
            "a title already called a-1 is skipped over",
        )
        assertEquals(listOf("a", "a-1", "a-1-1"), anchors("# a\n# a\n# a-1\n"))
    }

    @Test
    fun a_heading_with_no_words_has_an_empty_anchor() {
        assertEquals(listOf("", "-1"), anchors("#\n#\n"))
    }

    // --- Links and images ---

    private fun link(
        text: String,
        address: String,
    ) = MdSpan(text, link = address)

    @Test
    fun an_inline_link_keeps_its_address() { // Example 483
        assertEquals(listOf(para(link("link", "/uri"))), blocks("[link](/uri)\n"))
    }

    /**
     * The address is what the file says, Markdown's own escapes and references read; never
     * percent-encoded or otherwise prettied, since the confirmation shows exactly where it goes.
     */
    @Test
    fun an_address_is_kept_as_written() { // Examples 489, 502, 33's destination
        assertEquals(listOf(para(link("link", "/my uri"))), blocks("[link](</my uri>)\n"))
        assertEquals(listOf(para(link("link", "foo${bs}bar"))), blocks("[link](foo${bs}bar)\n"))
        assertEquals(listOf(para(link("link", "foo):"))), blocks("[link](foo$bs)$bs:)\n"))
        assertEquals(listOf(para(link("foo", "/föö"))), blocks("[foo](/f&ouml;&ouml;)\n"))
    }

    @Test
    fun a_title_is_not_shown_and_an_empty_address_is_kept() {
        assertEquals(listOf(para(link("link", "/url"))), blocks("[link](/url \"title\")\n"))
        assertEquals(listOf(para(link("link", ""))), blocks("[link]()\n"))
    }

    @Test
    fun a_links_text_keeps_its_styles() {
        assertEquals(
            listOf(para(MdSpan("em", emphasis = true, link = "u"), link(" text", "u"))),
            blocks("[*em* text](u)\n"),
        )
    }

    @Test
    fun reference_links_find_their_definition_and_the_definition_is_not_shown() { // Example 527
        assertEquals(listOf(para(link("foo", "/url"))), blocks("[foo][bar]\n\n[bar]: /url \"title\"\n"))
        assertEquals(listOf(para(link("foo", "/url"))), blocks("[foo]\n\n[foo]: /url\n"), "short")
        assertEquals(listOf(para(link("foo", "/url"))), blocks("[foo][]\n\n[foo]: /url\n"), "collapsed")
        assertEquals(listOf(para(link("Foo", "/url"))), blocks("[Foo]\n\n[foo]: /url\n"), "labels ignore case")
        assertEquals(listOf(para(link("x", "/url"))), blocks("[x][a  b]\n\n[A b]: /url\n"), "and runs of spaces")
    }

    @Test
    fun the_first_definition_of_a_label_wins() {
        assertEquals(listOf(para(link("foo", "/first"))), blocks("[foo]\n\n[foo]: /first\n[foo]: /second\n"))
    }

    @Test
    fun a_definition_inside_a_quote_still_counts() {
        assertEquals(
            listOf(para(link("foo", "/url")), MdBlock.Quote(emptyList())),
            blocks("[foo]\n\n> [foo]: /url\n"),
        )
    }

    @Test
    fun a_reference_with_no_definition_is_its_text() {
        assertEquals(listOf(para(plain("[bar][nope]"))), blocks("[bar][nope]\n"))
        assertEquals(listOf(para(plain("[nope]"))), blocks("[nope]\n"))
    }

    @Test
    fun autolinks() { // Examples 594, 604; GitHub's extended autolinks
        assertEquals(listOf(para(link("http://foo.bar.baz", "http://foo.bar.baz"))), blocks("<http://foo.bar.baz>\n"))
        assertEquals(
            listOf(para(link("foo@bar.example.com", "mailto:foo@bar.example.com"))),
            blocks("<foo@bar.example.com>\n"),
        )
        assertEquals(
            listOf(para(link("www.commonmark.org", "http://www.commonmark.org"))),
            blocks("www.commonmark.org\n"),
        )
        assertEquals(
            listOf(para(plain("Visit "), link("https://e.f/x", "https://e.f/x"), plain(" now"))),
            blocks("Visit https://e.f/x now\n"),
        )
    }

    /** What the parser does not support of GitHub's: a bare address is text, not a link. */
    @Test
    fun a_bare_email_address_is_text() {
        assertEquals(listOf(para(plain("foo@bar.baz"))), blocks("foo@bar.baz\n"))
    }

    @Test
    fun an_image_is_its_description_never_its_address() { // Examples 572, 573, 574, 575
        assertEquals(listOf(para(MdSpan("foo", image = true))), blocks("![foo](/url \"title\")\n"))
        assertEquals(
            listOf(para(MdSpan("foo bar", image = true))),
            blocks("![foo *bar*]\n\n[foo *bar*]: train.jpg \"train & tracks\"\n"),
        )
        assertEquals(listOf(para(MdSpan("foo bar", image = true))), blocks("![foo ![bar](/url)](/url2)\n"))
        assertEquals(listOf(para(MdSpan("foo bar", image = true))), blocks("![foo [bar](/url)](/url2)\n"))
    }

    /** An image with no description still marks its place, so the reader knows one was there. */
    @Test
    fun an_image_without_a_description_keeps_its_place() {
        assertEquals(listOf(para(plain("a "), MdSpan("", image = true), plain(" b"))), blocks("a ![](x.png) b\n"))
    }

    /** Images side by side are a run each, with nothing between them and no empty run of text. */
    @Test
    fun images_side_by_side_are_a_run_each() {
        assertEquals(
            listOf(para(plain("a "), MdSpan("x", image = true), MdSpan("y", image = true), plain(" b"))),
            blocks("a ![x](1.png)![y](2.png) b\n"),
        )
    }

    @Test
    fun an_image_reference_with_no_definition_is_its_text() {
        assertEquals(listOf(para(plain("![foo][nope]"))), blocks("![foo][nope]\n"))
    }

    @Test
    fun an_image_inside_a_link_carries_the_links_address() {
        assertEquals(
            listOf(para(MdSpan("alt", image = true, link = "https://x"))),
            blocks("[![alt](i.png)](https://x)\n"),
        )
    }

    // --- HTML and footnotes: shown as written ---

    @Test
    fun inline_html_is_shown_as_written() { // Example 613
        assertEquals(listOf(para(plain("<a><bab><c2c>"))), blocks("<a><bab><c2c>\n"))
        assertEquals(listOf(para(plain("a <span>hi</span> b"))), blocks("a <span>hi</span> b\n"))
        assertEquals(
            listOf(para(plain("<span title=\"&amp;\">x"))),
            blocks("<span title=\"&amp;\">x\n"),
            "a tag's references are not read",
        )
    }

    /** A block the reader has no drawing for — here GitHub's alert — is shown as written, never dropped. */
    @Test
    fun a_block_with_no_drawing_is_shown_as_written() {
        assertEquals(listOf(MdBlock.Raw("> [!NOTE]\n> hi")), blocks("> [!NOTE]\n> hi\n"))
    }

    @Test
    fun a_footnote_is_shown_as_written() {
        assertEquals(listOf(para(plain("foot[^1]")), MdBlock.Raw("[^1]: note")), blocks("foot[^1]\n\n[^1]: note\n"))
    }

    // --- What a file cannot make it do ---

    /**
     * Nesting is limited before parsing: the parser and this reading both recurse per level, and on iOS
     * a stack overflow ends the app — it cannot be caught. A line opening more than the allowed number
     * of quotes and list items, or indented further than allowed, makes the file "can't format" (shown
     * plain with a note), never a crash. Both sides of each limit.
     */
    @Test
    fun container_markers_at_a_lines_start_are_limited() {
        assertNotNull(MarkdownModel.from("> ".repeat(MARKDOWN_MAX_NESTING) + "x\n"))
        assertNull(MarkdownModel.from("> ".repeat(MARKDOWN_MAX_NESTING + 1) + "x\n"))
        assertNull(MarkdownModel.from(">".repeat(MARKDOWN_MAX_NESTING + 1) + " x\n"), "quote marks need no spaces")
        assertNotNull(MarkdownModel.from("> - ".repeat(MARKDOWN_MAX_NESTING / 2) + "x\n"))
        assertNull(
            MarkdownModel.from("> - ".repeat(MARKDOWN_MAX_NESTING / 2) + "> x\n"),
            "quotes and list items count together",
        )
        assertNull(MarkdownModel.from("1. ".repeat(MARKDOWN_MAX_NESTING + 1) + "x\n"))
        assertNull(MarkdownModel.from("1) ".repeat(MARKDOWN_MAX_NESTING + 1) + "x\n"))
        assertNull(MarkdownModel.from("-\t".repeat(MARKDOWN_MAX_NESTING + 1) + "x\n"), "a tab after the marker")
        assertNull(MarkdownModel.from("ok\r" + "> ".repeat(MARKDOWN_MAX_NESTING + 1) + "x\r"), "after a lone CR")
        assertNotNull(
            MarkdownModel.from("1.".repeat(MARKDOWN_MAX_NESTING + 1) + " x\n"),
            "a number is a marker only before . or ) and a space",
        )
        assertNull(MarkdownModel.from("+ ".repeat(MARKDOWN_MAX_NESTING + 1) + "x\n"))
        assertNull(
            MarkdownModel.from("ok\n\n" + "* ".repeat(MARKDOWN_MAX_NESTING + 1) + "x\n"),
            "on any line, not only the first",
        )
    }

    @Test
    fun indentation_at_a_lines_start_is_limited() {
        assertNotNull(MarkdownModel.from(" ".repeat(MARKDOWN_MAX_INDENT) + "x\n"))
        assertNull(MarkdownModel.from(" ".repeat(MARKDOWN_MAX_INDENT + 1) + "x\n"))
        assertNotNull(MarkdownModel.from("\t".repeat(MARKDOWN_MAX_INDENT / 4) + "x\n"), "a tab is four columns")
        assertNull(MarkdownModel.from("\t".repeat(MARKDOWN_MAX_INDENT / 4) + " x\n"))
        assertNull(
            MarkdownModel.from("> " + " ".repeat(MARKDOWN_MAX_INDENT) + "x\n"),
            "indentation after a marker counts too",
        )
    }

    /** What only looks like nesting is not limited: marks after a line's start, and a long rule. */
    @Test
    fun only_a_lines_opening_counts() {
        assertNotNull(MarkdownModel.from("x " + "> ".repeat(500) + "\n"))
        assertEquals(listOf<MdBlock>(MdBlock.Rule), MarkdownModel.from("-".repeat(500) + "\n")?.blocks)
        assertNotNull(MarkdownModel.from("*".repeat(500) + "\n"))
    }

    /** On a background thread, as the viewer reads it: the deepest nesting allowed reads without overflowing. */
    @Test
    fun the_deepest_nesting_allowed_reads_on_a_background_thread() =
        runTest {
            withContext(Dispatchers.Default) {
                assertNotNull(MarkdownModel.from("> ".repeat(MARKDOWN_MAX_NESTING) + "x\n"))
                assertNotNull(
                    MarkdownModel.from(
                        (0 until MARKDOWN_MAX_INDENT / 2).joinToString("\n") {
                            "  ".repeat(it) +
                                "- x"
                        },
                    ),
                )
                assertNotNull(MarkdownModel.from(nested("**", MARKDOWN_MAX_INLINE_NESTING)))
                assertNotNull(MarkdownModel.from("*_".repeat(5_000)), "unmatched marks nest nothing")
            }
        }

    /** [n] levels of [mark] around one word: `**a **a b a** a**` for two levels of `**`. */
    private fun nested(
        mark: String,
        n: Int,
    ) = "$mark${"a "}".repeat(n) + "b" + " a$mark".repeat(n)

    /**
     * Styles nest inside each other only as far as the limit; one level more and the
     * file is "can't format" (shown as written with a note) — never a stack overflow, which on iOS ends
     * the app and cannot be caught. Both sides of the limit.
     */
    @Test
    fun styles_nest_only_as_far_as_the_limit() {
        for (mark in listOf("**", "*", "~~")) {
            val deepest = assertNotNull(MarkdownModel.from(nested(mark, MARKDOWN_MAX_INLINE_NESTING)), mark).blocks
            // Every level inside the first has the same style, so the words are one run; the innermost is in it.
            assertTrue((deepest.single() as MdBlock.Paragraph).spans.any { " b " in it.text }, mark)
            assertNull(MarkdownModel.from(nested(mark, MARKDOWN_MAX_INLINE_NESTING + 1)), mark)
        }
    }

    /** Far past the limit, on a background thread as the viewer reads it — the stack the iOS app has there. */
    @Test
    fun a_file_nested_thousands_deep_is_cant_format_not_a_crash() =
        runTest {
            withContext(Dispatchers.Default) {
                for (mark in listOf("**", "*", "~~")) assertNull(MarkdownModel.from(nested(mark, 20_000)), mark)
            }
        }

    @Test
    fun the_inline_nesting_limit_is_sixty_four() {
        assertEquals(64, MARKDOWN_MAX_INLINE_NESTING)
    }

    /** Some inputs take the parser time that grows with the square of their size; a budget bounds it. */
    @Test
    fun reading_past_its_time_budget_is_cant_format() {
        val markdown = "# a\n\ntext *b*\n\n- c\n- d\n\n> e\n"
        val clock = TestTimeSource()
        // Each check the parser makes moves the clock on a second.
        val ticking = {
            clock += 1.seconds
            false
        }
        assertNull(MarkdownModel.from(markdown, budget = 2.seconds, cancelled = ticking, clock = clock))
        assertNotNull(MarkdownModel.from(markdown, budget = 1_000.seconds, cancelled = ticking, clock = clock))
        // The budget is spent once it is reached, not only once it is passed: a zero budget never parses.
        assertNull(MarkdownModel.from(markdown, budget = Duration.ZERO, clock = TestTimeSource()))
    }

    @Test
    fun the_time_budget_is_ten_seconds() {
        assertEquals(10.seconds, MARKDOWN_TIME_BUDGET)
    }

    /** A lock or a swipe away stops the parse where it is, rather than letting it run to the end. */
    @Test
    fun a_cancelled_read_stops_with_a_cancellation() {
        assertFailsWith<CancellationException> { MarkdownModel.from("# a\n\ntext\n", cancelled = { true }) }
    }

    /**
     * Twenty thousand brackets: the parser checks only a handful of times on its way through them, and
     * reading the tree after it meets every one. A budget and a cancel only the parser checked would let
     * that reading run on for as long as it takes — past the budget, and past a lock.
     */
    private val manyTokens = "[".repeat(20_000)

    @Test
    fun the_time_budget_covers_reading_the_tree_too() {
        val clock = TestTimeSource()
        var checks = 0
        val ticking = {
            checks += 1
            clock += 1.seconds
            false
        }
        assertNull(MarkdownModel.from(manyTokens, budget = 1_000.seconds, cancelled = ticking, clock = clock))
        assertTrue(checks >= 1_000, "the reading checked as it went, up to the budget")
    }

    /** Two hundred one-word paragraphs: the parser checks thousands of times, the reading a few hundred. */
    @Test
    fun a_cancel_during_the_parse_stops_it() {
        var checks = 0
        assertFailsWith<CancellationException> {
            MarkdownModel.from(
                "a\n\n".repeat(200),
                cancelled = { ++checks > 1_000 },
            )
        }
    }

    @Test
    fun a_cancel_while_reading_the_tree_stops_it() {
        var checks = 0
        assertFailsWith<CancellationException> { MarkdownModel.from(manyTokens, cancelled = { ++checks > 1_000 }) }
    }

    /**
     * The model holds a decrypted file's words, links and code; a printout of it — a log line, a failed
     * check's message, a debugger — shows counts and kinds, never them.
     */
    @Test
    fun the_model_prints_counts_never_the_files_words() {
        val secret = "SECRET"
        val span = MdSpan(secret, strong = true, link = "https://$secret.example/")
        val printed =
            listOf(
                span,
                MdBlock.Heading(1, listOf(span), secret),
                MdBlock.Paragraph(listOf(span)),
                MdBlock.Code(secret, secret),
                MdBlock.Quote(listOf(MdBlock.Paragraph(listOf(span)))),
                MdBlock.ListBlock(1, false, listOf(MdListItem(null, listOf(MdBlock.Paragraph(listOf(span)))))),
                MdListItem(true, listOf(MdBlock.Raw(secret))),
                MdBlock.Table(listOf(CellAlign.Start), listOf(listOf(span)), listOf(listOf(listOf(span)))),
                MdBlock.Raw(secret),
            ).map { it.toString() }
        for (text in printed) assertTrue(secret !in text, text)
        assertEquals("MdSpan(6 chars, strong, link)", printed[0])
        assertEquals("Raw(6 chars)", printed.last())
    }

    @Test
    fun an_html_block_is_shown_as_written() { // Example 160
        assertEquals(
            listOf(MdBlock.Raw("<table><tr><td>\nfoo\n</td></tr></table>")),
            blocks("<table><tr><td>\nfoo\n</td></tr></table>\n"),
        )
    }
}
