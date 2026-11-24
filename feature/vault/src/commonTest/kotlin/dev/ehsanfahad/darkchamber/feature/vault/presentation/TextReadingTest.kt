package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.FormatNote
import dev.ehsanfahad.darkchamber.feature.vault.api.TextKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A text page's rules: what it shows in which font, when the switch shows, and the thumb by lines. */
class TextReadingTest {

    // ---- what is shown, in which font ------------------------------------------------------------

    @Test
    fun prose_reads_in_the_reading_font_and_code_in_monospace_whatever_the_switch() {
        for (formatted in listOf(true, false)) {
            assertEquals(TextView.Lines(indented = false, font = TextFont.Reading), TextReading.view(TextKind.Plain, false, formatted))
            assertEquals(TextView.Lines(indented = false, font = TextFont.Mono), TextReading.view(TextKind.Code, false, formatted))
        }
    }

    @Test
    fun markdown_formatted_is_drawn_and_plain_is_its_source_in_monospace() {
        assertEquals(TextView.Markdown, TextReading.view(TextKind.Markdown, canFormat = true, formatted = true))
        assertEquals(TextView.Lines(indented = false, font = TextFont.Mono), TextReading.view(TextKind.Markdown, canFormat = true, formatted = false))
    }

    @Test
    fun json_formatted_is_the_indented_lines_and_plain_the_stored_ones_both_monospace() {
        assertEquals(TextView.Lines(indented = true, font = TextFont.Mono), TextReading.view(TextKind.Json, canFormat = true, formatted = true))
        assertEquals(TextView.Lines(indented = false, font = TextFont.Mono), TextReading.view(TextKind.Json, canFormat = true, formatted = false))
    }

    /** No formatted view (too large, too complex, not valid JSON): as written, whatever the switch says. */
    @Test
    fun a_file_that_cannot_be_formatted_is_shown_as_written() {
        for (kind in listOf(TextKind.Markdown, TextKind.Json)) {
            assertEquals(TextView.Lines(indented = false, font = TextFont.Mono), TextReading.view(kind, canFormat = false, formatted = true))
        }
    }

    // ---- the switch ------------------------------------------------------------------------------

    @Test
    fun the_switch_shows_only_where_there_is_a_formatted_view() {
        assertTrue(TextReading.hasSwitch(TextKind.Markdown, canFormat = true))
        assertTrue(TextReading.hasSwitch(TextKind.Json, canFormat = true))
        assertFalse(TextReading.hasSwitch(TextKind.Markdown, canFormat = false))
        assertFalse(TextReading.hasSwitch(TextKind.Json, canFormat = false))
        assertFalse(TextReading.hasSwitch(TextKind.Plain, canFormat = true))
        assertFalse(TextReading.hasSwitch(TextKind.Code, canFormat = true))
    }

    // ---- the thumb, by rows ----------------------------------------------------------------------

    /** Worth a thumb past five screens of rows, as a PDF past five screens of pages. */
    @Test
    fun a_thumb_is_worth_having_past_five_screens_of_rows() {
        assertFalse(TextReading.worthIt(rowCount = 100, visibleRows = 20))
        assertTrue(TextReading.worthIt(rowCount = 101, visibleRows = 20))
        assertFalse(TextReading.worthIt(rowCount = 101, visibleRows = 0), "nothing laid out yet")
    }

    @Test
    fun the_fraction_is_rows_above_the_screen_out_of_how_many_can_be() {
        // 1,000 rows, 20 on screen: 980 can be above it.
        assertEquals(0f, TextReading.fraction(0, 0f, 1_000, 20, atEnd = false))
        assertEquals(0.5f, TextReading.fraction(490, 0f, 1_000, 20, atEnd = false))
        // 820 rows, 20 on screen: 800 can be above; 199 and a half of them are.
        assertEquals(0.249375f, TextReading.fraction(199, 0.5f, 820, 20, atEnd = false), 0.000001f, "part of the first row counts")
    }

    /** Rows on screen change with their lengths; at the end the thumb is at the bottom regardless. */
    @Test
    fun at_the_end_the_fraction_is_one_and_never_past_it() {
        assertEquals(1f, TextReading.fraction(900, 0f, 1_000, 20, atEnd = true))
        assertEquals(1f, TextReading.fraction(995, 0f, 1_000, 20, atEnd = false))
        assertEquals(0f, TextReading.fraction(0, 0f, 10, 20, atEnd = false), "all on one screen")
    }

    @Test
    fun a_drag_brings_its_row_to_the_top_and_the_end_brings_the_last_row() {
        assertEquals(0, TextReading.rowAt(0f, 1_000, 20))
        assertEquals(490, TextReading.rowAt(0.5f, 1_000, 20))
        assertEquals(999, TextReading.rowAt(1f, 1_000, 20), "the whole way down: the end shows")
        assertEquals(0, TextReading.rowAt(-0.2f, 1_000, 20))
        assertEquals(0, TextReading.rowAt(0.5f, 0, 20), "no rows")
        assertEquals(0, TextReading.rowAt(1f, 0, 20), "no rows, the whole way down: never row -1")
    }

    // ---- rows ------------------------------------------------------------------------------------

    private fun TextRows.all() = List(count) { row(it) }

    private fun TextRows.lines() = List(count) { lineOf(it) }

    @Test
    fun a_short_line_is_one_row_and_an_empty_line_is_an_empty_row() {
        val rows = TextReading.rows(TextDecoding.lines("one\n\nthree"), maxChars = 10)
        assertEquals(listOf("one", "", "three"), rows.all())
        assertEquals(listOf(0, 1, 2), rows.lines())
    }

    /** The crash this guards: one minified line laid out as one item taller than a layout may be. */
    @Test
    fun a_long_line_with_no_space_is_cut_at_the_room() {
        val rows = TextReading.rows(TextDecoding.lines("x\n" + "a".repeat(25) + "\ny"), maxChars = 10)
        assertEquals(listOf("x", "a".repeat(10), "a".repeat(10), "a".repeat(5), "y"), rows.all())
        assertEquals(listOf(0, 1, 1, 1, 2), rows.lines(), "each piece still belongs to its stored line")
    }

    @Test
    fun a_line_exactly_the_room_is_not_cut() {
        assertEquals(listOf("a".repeat(10)), TextReading.rows(TextDecoding.lines("a".repeat(10)), maxChars = 10).all())
    }

    /** A space in the last quarter of the room: cut after it, so no word is split. */
    @Test
    fun a_long_line_is_cut_after_a_space_near_the_end_of_the_room() {
        // Room 20; the last quarter is characters 15..19. A space at 16 is cut after.
        val line = "a".repeat(16) + " " + "b".repeat(10)
        assertEquals(listOf("a".repeat(16) + " ", "b".repeat(10)), TextReading.rows(TextDecoding.lines(line), maxChars = 20).all())
    }

    /** A space early in the room would leave a short row; the room's end is used instead. */
    @Test
    fun a_space_before_the_last_quarter_is_not_cut_at() {
        // Room 20, last quarter 15..19: a space at 12 is in the room's second half but not its last quarter.
        val line = "a".repeat(12) + " " + "b".repeat(30)
        val rows = TextReading.rows(TextDecoding.lines(line), maxChars = 20).all()
        assertEquals(20, rows[0].length)
        assertEquals(line, rows.joinToString(""))
    }

    /** A room of one could never hold a whole pair, and the cut would stand still for ever. */
    @Test
    fun a_room_too_small_for_a_whole_character_is_refused() {
        assertFailsWith<IllegalArgumentException> { TextReading.rows(TextDecoding.lines("abc"), maxChars = 1) }
    }

    @Test
    fun a_cut_never_parts_the_two_halves_of_a_character() {
        val face = "" + Char(0xD83D) + Char(0xDE00)
        // The pair falls at characters 9..10 of a room of 10: the cut moves before it.
        val line = "a".repeat(9) + face + "a".repeat(5)
        val rows = TextReading.rows(TextDecoding.lines(line), maxChars = 10).all()
        assertEquals(listOf("a".repeat(9), face + "a".repeat(5)), rows)
    }

    @Test
    fun every_character_is_in_one_row_in_order() {
        val text = (1..50).joinToString("\n") { n -> "word ".repeat(n) + "x".repeat(n * 3) }
        val decoded = TextDecoding.lines(text)
        val rows = TextReading.rows(decoded, maxChars = 37)
        for (line in 0 until decoded.lineCount) {
            val pieces = (0 until rows.count).filter { rows.lineOf(it) == line }.map { rows.row(it) }
            assertEquals(decoded.line(line), pieces.joinToString(""), "line $line")
            assertTrue(pieces.all { it.length <= 37 })
        }
    }

    // ---- screen readers --------------------------------------------------------------------------

    @Test
    fun a_blank_line_is_passed_over_and_any_other_is_said_as_written() {
        assertNull(TextReading.spoken(""))
        assertNull(TextReading.spoken("   " + Char(0x09)))
        assertEquals("  indented", TextReading.spoken("  indented"))
    }

    // ---- words -----------------------------------------------------------------------------------

    @Test
    fun the_switch_names_the_view_shown_and_says_what_a_tap_does() {
        assertEquals("Formatted", ViewerFormat.textViewName(formatted = true))
        assertEquals("Plain", ViewerFormat.textViewName(formatted = false))
        assertEquals("Show as written", ViewerFormat.textViewAction(formatted = true))
        assertEquals("Show formatted", ViewerFormat.textViewAction(formatted = false))
    }

    @Test
    fun the_thumbs_bubble_counts_lines_from_one() {
        assertEquals("Line 1", ViewerFormat.fastScrollLine(0))
        assertEquals("Line 1000", ViewerFormat.fastScrollLine(999), "ungrouped, as the viewer's other counts")
    }

    /** Read and verified: the limit is this viewer's, never the file's state. */
    @Test
    fun a_note_says_why_there_is_no_formatted_view_and_nothing_about_the_file() {
        assertEquals("Shown as written: too large to format here.", ViewerFormat.formatNote(FormatNote.TooLarge))
        assertEquals("Shown as written: too complex to format here.", ViewerFormat.formatNote(FormatNote.TooComplex))
        assertEquals("Shown as written: not valid JSON, so it can't be formatted.", ViewerFormat.formatNote(FormatNote.NotJson))
        val words = FormatNote.entries.joinToString(" ") { ViewerFormat.formatNote(it) }.lowercase()
        for (claim in listOf("damaged", "corrupt", "broken", "changed", "error")) assertFalse(claim in words, claim)
    }
}
