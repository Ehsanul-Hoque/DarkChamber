package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
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
            assertEquals(
                TextView.Lines(indented = false, font = TextFont.Reading),
                TextReading.view(TextKind.Plain, false, formatted),
            )
            assertEquals(
                TextView.Lines(indented = false, font = TextFont.Mono),
                TextReading.view(TextKind.Code, false, formatted),
            )
        }
    }

    @Test
    fun markdown_formatted_is_drawn_and_plain_is_its_source_in_monospace() {
        assertEquals(TextView.Markdown, TextReading.view(TextKind.Markdown, canFormat = true, formatted = true))
        assertEquals(
            TextView.Lines(indented = false, font = TextFont.Mono),
            TextReading.view(TextKind.Markdown, canFormat = true, formatted = false),
        )
    }

    @Test
    fun json_formatted_is_the_indented_lines_and_plain_the_stored_ones_both_monospace() {
        assertEquals(
            TextView.Lines(indented = true, font = TextFont.Mono),
            TextReading.view(TextKind.Json, canFormat = true, formatted = true),
        )
        assertEquals(
            TextView.Lines(indented = false, font = TextFont.Mono),
            TextReading.view(TextKind.Json, canFormat = true, formatted = false),
        )
    }

    /** No formatted view (too large, too complex, not valid JSON): as written, whatever the switch says. */
    @Test
    fun a_file_that_cannot_be_formatted_is_shown_as_written() {
        for (kind in listOf(TextKind.Markdown, TextKind.Json)) {
            assertEquals(
                TextView.Lines(indented = false, font = TextFont.Mono),
                TextReading.view(kind, canFormat = false, formatted = true),
            )
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

    // ---- the thumb (its place is RowHeightsTest's) -----------------------------------------------

    /**
     * The column's height is estimated from the rows seen so far, so whether it is long enough can
     * change as it scrolls; a thumb shown once stays for the page, and one being dragged never goes.
     */
    @Test
    fun a_thumb_once_worth_having_stays() {
        assertTrue(TextReading.keepsThumb(shownBefore = true, worthNow = false, dragging = false))
        assertTrue(TextReading.keepsThumb(shownBefore = false, worthNow = true, dragging = false))
        assertTrue(TextReading.keepsThumb(shownBefore = false, worthNow = false, dragging = true))
        assertFalse(TextReading.keepsThumb(shownBefore = false, worthNow = false, dragging = false))
    }

    /**
     * Compose trims the room a line height adds above a text's first line and below its last; only a
     * block's own ends keep that trim, so a row that continues one — every plain row, a cut piece's
     * inner side — is spaced exactly like a wrapped line.
     */
    @Test
    fun only_a_blocks_own_ends_are_trimmed() {
        assertEquals(LineHeightStyle.Trim.Both, TextReading.trim(first = true, last = true))
        assertEquals(LineHeightStyle.Trim.FirstLineTop, TextReading.trim(first = true, last = false))
        assertEquals(LineHeightStyle.Trim.LastLineBottom, TextReading.trim(first = false, last = true))
        assertEquals(LineHeightStyle.Trim.None, TextReading.trim(first = false, last = false))
    }

    @Test
    fun a_spaced_style_changes_only_its_line_height_style() {
        val style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
        val spaced = TextReading.spaced(style, first = false, last = true)
        assertEquals(
            LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.LastLineBottom),
            spaced.lineHeightStyle,
        )
        assertEquals(style.copy(lineHeightStyle = spaced.lineHeightStyle), spaced)
    }

    /** Formatted Markdown: a thumb as soon as the column scrolls, however short. */
    @Test
    fun formatted_markdown_is_worth_a_thumb_whenever_it_scrolls() {
        assertTrue(
            TextReading.worthAThumb(whenScrolls = true, canScroll = true, contentHeight = 1100, viewportHeight = 1000),
        )
        assertFalse(
            TextReading.worthAThumb(whenScrolls = true, canScroll = false, contentHeight = 9000, viewportHeight = 1000),
        )
    }

    /** Every other page: past five screens, scrolling or not (both sides of the limit). */
    @Test
    fun other_pages_are_worth_a_thumb_past_five_screens() {
        assertTrue(
            TextReading.worthAThumb(whenScrolls = false, canScroll = true, contentHeight = 5001, viewportHeight = 1000),
        )
        assertFalse(
            TextReading.worthAThumb(whenScrolls = false, canScroll = true, contentHeight = 5000, viewportHeight = 1000),
        )
    }

    /** The last row may be taller than the screen: the whole way down is its end, not its top. */
    @Test
    fun only_the_whole_way_down_goes_to_the_very_end() {
        assertTrue(TextReading.toTheEnd(1f))
        assertFalse(TextReading.toTheEnd(0.999f))
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
        assertEquals(
            listOf("a".repeat(16) + " ", "b".repeat(10)),
            TextReading.rows(TextDecoding.lines(line), maxChars = 20).all(),
        )
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
        assertEquals(
            "Shown as written: not valid JSON, so it can't be formatted.",
            ViewerFormat.formatNote(FormatNote.NotJson),
        )
        val words = FormatNote.entries.joinToString(" ") { ViewerFormat.formatNote(it) }.lowercase()
        for (claim in listOf("damaged", "corrupt", "broken", "changed", "error")) assertFalse(claim in words, claim)
    }
}
