package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a Markdown document becomes the page's rows: one per block, or several where a block is long. */
class MarkdownRowsTest {
    private fun words(text: String) = listOf(MdSpan(text))

    private fun para(text: String) = MdBlock.Paragraph(words(text))

    private fun rows(vararg blocks: MdBlock, maxChars: Int = 2_000) = MarkdownRows.rows(MarkdownDocument(blocks.toList()), maxChars)

    private fun MdRow.text(): String = when (this) {
        is MdRow.Words -> spans.joinToString("") { it.text }
        is MdRow.Code -> text
        is MdRow.Raw -> text
        else -> ""
    }

    // ---- one block, one row ----------------------------------------------------------------------

    @Test
    fun headings_take_their_size_from_their_level_and_paragraphs_are_body() {
        val r = rows(
            MdBlock.Heading(1, words("One"), "one"),
            MdBlock.Heading(2, words("Two"), "two"),
            MdBlock.Heading(3, words("Three"), "three"),
            MdBlock.Heading(6, words("Six"), "six"),
            para("Body"),
        )
        assertEquals(
            listOf(MdTextSize.Title1, MdTextSize.Title2, MdTextSize.Title3, MdTextSize.Title3, MdTextSize.Body),
            r.map { (it as MdRow.Words).size },
        )
        assertEquals(listOf(0, 1, 2, 3, 4), r.map { it.block })
    }

    @Test
    fun space_comes_between_blocks_but_not_before_the_first() {
        val r = rows(para("a"), MdBlock.Rule, para("b"))
        assertEquals(listOf(false, true, true), r.map { it.gapAbove })
        assertTrue(r[1] is MdRow.Rule)
    }

    // ---- long blocks become several rows ---------------------------------------------------------

    /** The crash a plain page had: one block as one item taller than a layout may be. */
    @Test
    fun a_long_paragraph_is_cut_into_rows_that_keep_its_styles() {
        val spans = listOf(MdSpan("a".repeat(15)), MdSpan("b".repeat(10), strong = true))
        val r = rows(MdBlock.Paragraph(spans), maxChars = 10)
        assertEquals(listOf("aaaaaaaaaa", "aaaaabbbbb", "bbbbb"), r.map { it.text() })
        val middle = (r[1] as MdRow.Words).spans
        assertEquals(listOf(MdSpan("aaaaa"), MdSpan("bbbbb", strong = true)), middle)
        assertEquals(listOf(false, false, false), r.map { it.gapAbove }, "one paragraph: no space inside it")
        assertEquals(listOf(0, 0, 0), r.map { it.block })
    }

    @Test
    fun only_the_first_piece_of_a_long_paragraph_has_space_above_it() {
        val r = rows(para("x"), para("a".repeat(25)), maxChars = 10)
        assertEquals(listOf(false, true, false, false), r.map { it.gapAbove })
    }

    /** A cut that falls where one run ends and the next begins leaves no empty run behind. */
    @Test
    fun a_cut_on_a_run_boundary_leaves_no_empty_run() {
        val r = rows(MdBlock.Paragraph(listOf(MdSpan("a".repeat(10)), MdSpan("b".repeat(10), emphasis = true))), maxChars = 10)
        assertEquals(listOf(listOf(MdSpan("a".repeat(10))), listOf(MdSpan("b".repeat(10), emphasis = true))), r.map { (it as MdRow.Words).spans })
    }

    @Test
    fun a_code_piece_counts_the_line_breaks_between_its_lines() {
        val code = listOf("aaaa", "bbbb", "cccc", "dddd").joinToString("\n")
        val r = rows(MdBlock.Code(code, null), maxChars = 13)
        assertEquals(listOf("aaaa\nbbbb", "cccc\ndddd"), r.map { it.text() })
    }

    @Test
    fun a_long_paragraph_is_cut_after_a_space_near_the_end_and_loses_no_character() {
        val text = "word ".repeat(40)
        val r = rows(para(text), maxChars = 20)
        assertEquals(text, r.joinToString("") { it.text() })
        assertTrue(r.all { it.text().length <= 20 })
        assertTrue(r.dropLast(1).all { it.text().endsWith(" ") }, "cut after spaces")
    }

    @Test
    fun a_long_code_block_is_cut_into_pieces_of_whole_lines_one_width() {
        val code = (1..30).joinToString("\n") { "line $it" + "x".repeat(it) }
        val r = rows(MdBlock.Code(code, "kotlin"), maxChars = 100)
        assertTrue(r.size > 1)
        val pieces = r.map { it as MdRow.Code }
        assertEquals(code, pieces.joinToString("\n") { it.text }, "whole lines, every one")
        assertTrue(pieces.all { it.text.length <= 100 })
        assertEquals(setOf("line 30".length + 30), pieces.map { it.widthChars }.toSet(), "the longest line, for every piece")
        assertEquals(listOf(true) + List(pieces.size - 1) { false }, pieces.map { it.first })
        assertEquals(List(pieces.size - 1) { false } + listOf(true), pieces.map { it.last })
    }

    /** One line of code longer than a row: cut too, or the unwrapped line is wider than a layout may be. */
    @Test
    fun one_code_line_longer_than_a_row_is_cut_too() {
        val r = rows(MdBlock.Code("z".repeat(25), null), maxChars = 10)
        assertTrue(r.all { (it as MdRow.Code).text.length <= 10 })
        assertEquals("z".repeat(25), r.joinToString("") { it.text().replace("\n", "") })
        assertEquals(setOf(10), r.map { (it as MdRow.Code).widthChars }.toSet())
    }

    @Test
    fun raw_html_is_cut_like_code() {
        val raw = (1..20).joinToString("\n") { "<p>$it</p>" }
        val r = rows(MdBlock.Raw(raw), maxChars = 40)
        assertTrue(r.size > 1 && r.all { it is MdRow.Raw && it.text.length <= 40 })
        assertEquals(raw, r.joinToString("\n") { it.text() })
    }

    @Test
    fun a_long_table_is_drawn_in_pieces_with_its_header_once_and_one_set_of_widths() {
        val header = listOf(words("Name"), words("Notes"))
        val body = List(120) { i -> listOf(words("n$i"), words("x".repeat(i % 50))) }
        val r = rows(para("before"), MdBlock.Table(listOf(CellAlign.Start, CellAlign.End), header, body)).drop(1)
        val pieces = r.map { it as MdRow.TableRows }
        // Ten rows a piece: a piece stays shorter than a screen, so the thumb can reach every part of it.
        assertEquals(List(12) { 10 }, pieces.map { it.rows.size })
        assertEquals(header, pieces[0].header)
        assertTrue(pieces.drop(1).all { it.header == null })
        assertEquals(body, pieces.flatMap { it.rows })
        // "n119" is 4, "Name" 4: the first column 4 wide; the second's longest cell is 49, capped at 30.
        assertEquals(setOf(listOf(4, 30)), pieces.map { it.widths }.toSet())
        assertEquals(listOf(true) + List(11) { false }, pieces.map { it.gapAbove })
    }

    /** Short lines are cut by count too, so a piece of code stays shorter than a screen. */
    @Test
    fun a_code_piece_holds_at_most_twenty_lines() {
        val code = (1..45).joinToString("\n") { "l$it" }
        val pieces = rows(MdBlock.Code(code, null)).map { (it as MdRow.Code).text.lines().size }
        assertEquals(listOf(20, 20, 5), pieces)
    }

    @Test
    fun a_table_column_is_never_narrower_than_one_character() {
        val r = rows(MdBlock.Table(listOf(CellAlign.Start), listOf(words("")), listOf(listOf(words("")))))
        assertEquals(listOf(1), (r.single() as MdRow.TableRows).widths)
    }

    /** A row may hold fewer cells than the header names; the column is still measured from what is there. */
    @Test
    fun a_table_with_a_short_row_measures_every_column() {
        val r = rows(MdBlock.Table(listOf(CellAlign.Start, CellAlign.Center), listOf(words("A"), words("Bee")), listOf(listOf(words("long one")))))
        assertEquals(listOf(8, 3), (r.single() as MdRow.TableRows).widths)
    }

    /** Pieces of one code block or table scroll sideways together; two blocks never share a scroll. */
    @Test
    fun the_pieces_of_one_block_share_a_group_and_two_blocks_do_not() {
        val code = (1..30).joinToString("\n") { "line $it" }
        val table = MdBlock.Table(listOf(CellAlign.Start), listOf(words("h")), List(60) { listOf(words("c")) })
        val item = MdListItem(null, listOf(MdBlock.Code(code, null), MdBlock.Code(code, null)))
        val r = rows(MdBlock.ListBlock(null, false, listOf(item)), table, maxChars = 100)
        val groups = r.mapNotNull {
            when (it) {
                is MdRow.Code -> it.group
                is MdRow.TableRows -> it.group
                else -> null
            }
        }
        val codeGroups = r.filterIsInstance<MdRow.Code>().map { it.group }
        val tableGroups = r.filterIsInstance<MdRow.TableRows>().map { it.group }
        assertEquals(3, groups.toSet().size, "two code blocks and a table: ${groups}")
        assertEquals(1, tableGroups.toSet().size)
        assertTrue(codeGroups.zipWithNext().count { (a, b) -> a != b } == 1, "the second code block starts a group of its own")
    }

    // ---- lists and quotes ------------------------------------------------------------------------

    @Test
    fun a_bulleted_list_marks_each_items_first_row_and_indents_its_blocks() {
        val list = MdBlock.ListBlock(
            start = null,
            loose = false,
            items = listOf(MdListItem(null, listOf(para("one"), para("more"))), MdListItem(null, listOf(para("two")))),
        )
        val r = rows(list).map { it as MdRow.Words }
        assertEquals(listOf("one", "more", "two"), r.map { it.text() })
        assertEquals(listOf(MarkdownRows.bullet(0), null, MarkdownRows.bullet(0)), r.map { it.marker })
        assertEquals(listOf(1, 1, 1), r.map { it.indent })
        assertEquals(listOf(false, true, false), r.map { it.gapAbove }, "tight: no space between items; blocks in an item still part")
    }

    @Test
    fun a_loose_numbered_list_counts_from_its_start_with_space_between_items() {
        val list = MdBlock.ListBlock(start = 7, loose = true, items = listOf(MdListItem(null, listOf(para("a"))), MdListItem(null, listOf(para("b")))))
        val r = rows(para("before"), list).drop(1).map { it as MdRow.Words }
        assertEquals(listOf("7.", "8."), r.map { it.marker })
        assertEquals(listOf("7.", "8."), r.map { it.markerSpoken })
        assertEquals(listOf(true, true), r.map { it.gapAbove })
    }

    @Test
    fun a_task_is_marked_with_its_box_and_said_as_done_or_not() {
        val list = MdBlock.ListBlock(null, false, listOf(MdListItem(true, listOf(para("did"))), MdListItem(false, listOf(para("todo")))))
        val r = rows(list).map { it as MdRow.Words }
        assertEquals(listOf("" + Char(0x2611), "" + Char(0x2610)), r.map { it.marker })
        assertEquals(listOf("Done", "Not done"), r.map { it.markerSpoken })
    }

    /** A long item's mark is on its first row only. */
    @Test
    fun a_long_items_mark_is_on_its_first_piece_only() {
        val list = MdBlock.ListBlock(null, false, listOf(MdListItem(null, listOf(para("a".repeat(25))))))
        val r = rows(list, maxChars = 10).map { it as MdRow.Words }
        assertEquals(listOf(MarkdownRows.bullet(0), null, null), r.map { it.marker })
    }

    /** An item that opens with code still shows its marker, on a row of its own. */
    @Test
    fun an_item_that_opens_with_something_other_than_words_gets_a_marker_row() {
        val list = MdBlock.ListBlock(null, false, listOf(MdListItem(null, listOf(MdBlock.Code("x", null)))))
        val r = rows(para("before"), list).drop(1)
        val marker = r[0] as MdRow.Words
        assertEquals(MarkdownRows.bullet(0), marker.marker)
        assertEquals(emptyList(), marker.spans)
        assertTrue(marker.gapAbove, "the item's space goes above its mark")
        assertTrue(r[1] is MdRow.Code)
        assertFalse(r[1].gapAbove, "and none between the mark and what it marks")
    }

    @Test
    fun nested_lists_take_the_next_bullet_and_indent_further() {
        val inner = MdBlock.ListBlock(null, false, listOf(MdListItem(null, listOf(para("inner")))))
        val outer = MdBlock.ListBlock(null, false, listOf(MdListItem(null, listOf(para("outer"), inner))))
        val r = rows(outer).map { it as MdRow.Words }
        assertEquals(listOf(MarkdownRows.bullet(0), MarkdownRows.bullet(1)), r.map { it.marker })
        assertEquals(listOf(1, 2), r.map { it.indent })
    }

    @Test
    fun bullets_go_round_by_depth() {
        assertEquals(listOf("" + Char(0x2022), "" + Char(0x25E6), "" + Char(0x25AA)), (0..2).map(MarkdownRows::bullet))
        assertEquals(MarkdownRows.bullet(0), MarkdownRows.bullet(3))
    }

    @Test
    fun a_quote_counts_its_bars_and_keeps_its_block_number() {
        val r = rows(para("x"), MdBlock.Quote(listOf(para("q"), MdBlock.Quote(listOf(para("qq"))))))
        assertEquals(listOf(0, 1, 2), r.map { it.quotes })
        assertEquals(listOf(0, 1, 1), r.map { it.block }, "everything inside block 1 lands with it")
    }

    @Test
    fun the_blocks_of_a_quote_have_space_between_them() {
        val r = rows(MdBlock.Quote(listOf(para("a"), para("b"))))
        assertEquals(listOf(false, true), r.map { it.gapAbove })
    }

    // ---- sections --------------------------------------------------------------------------------

    @Test
    fun a_section_lands_on_its_blocks_first_row() {
        val r = rows(para("a".repeat(25)), MdBlock.Heading(2, words("Here"), "here"), maxChars = 10)
        assertEquals(0, MarkdownRows.firstRowOf(r, 0))
        assertEquals(3, MarkdownRows.firstRowOf(r, 1))
        assertNull(MarkdownRows.firstRowOf(r, 2))
    }
}
