package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** A plain page's row heights, estimated before the rows are laid out. */
class ScreenLinesTest {

    /** Every character [px] wide, as a monospace font draws them. */
    private fun mono(px: Float = 10f, chars: String = " ab") =
        LetterWidths(chars.map { it.code }.toIntArray(), FloatArray(chars.length) { px })

    private fun lines(text: String, width: Float, widths: LetterWidths = mono()) =
        ScreenLines.count(text, 0, text.length, width, widths)

    // An emoji outside the basic plane, as its two UTF-16 halves.
    private val grin = "" + Char(0xD83D) + Char(0xDE00)
    private val grinCode = 0x1F600

    @Test
    fun an_empty_row_is_one_line() {
        assertEquals(1, lines("", 100f))
    }

    /** Both sides of the width: a word exactly as wide as the line fits; a hair wider breaks. */
    @Test
    fun a_word_as_wide_as_the_line_fits_and_one_wider_is_broken_between_characters() {
        assertEquals(1, lines("aaaa", 40f))
        assertEquals(2, lines("aaaa", 39.9f))
        assertEquals(3, lines("aaaaaaaaaa", 40f))
    }

    @Test
    fun words_wrap_at_the_space_between_them() {
        assertEquals(1, lines("aa aa", 50f))
        assertEquals(2, lines("aa aa", 49f))
        assertEquals(2, lines("aa aa", 20f))
        assertEquals(3, lines("ab ab ab", 20f))
    }

    /** Spaces after a line's last word hang past its end; they never make a line of their own. */
    @Test
    fun spaces_at_the_end_of_a_line_hang() {
        assertEquals(1, lines("aa    ", 20f))
        assertEquals(2, lines("aa    aa", 20f))
    }

    /** Spaces at the very start of a row take room like any character. */
    @Test
    fun spaces_opening_a_row_take_room() {
        assertEquals(1, lines("  aa", 40f))
        assertEquals(2, lines("  aa", 30f))
    }

    /**
     * A word too wide for any line goes to a fresh line first, then breaks between characters — not
     * filling the end of the line it follows ("a aa" | "aaa" would be two).
     */
    @Test
    fun a_long_word_after_a_short_one_starts_a_fresh_line() {
        assertEquals(3, lines("a aaaaa", 40f))
        // The fresh line starts empty: "a" | "aaaa" | "aaaa".
        assertEquals(3, lines("a aaaaaaaa", 40f))
    }

    /** A character wider than the whole line still takes one line, never an empty one before it. */
    @Test
    fun a_character_wider_than_the_line_takes_one_line() {
        assertEquals(1, lines("a", 5f))
        assertEquals(2, lines("aa", 5f))
    }

    @Test
    fun only_the_rows_own_range_is_read() {
        assertEquals(1, ScreenLines.count("aaaaaaaa ab", 9, 11, 20f, mono()))
    }

    /** A character outside the basic plane is one character with its own width, never two halves. */
    @Test
    fun a_character_beyond_the_basic_plane_is_measured_whole() {
        // Not the widest, so its own width is told apart from the stand-in for an unmeasured one.
        val widths = LetterWidths(intArrayOf('a'.code, 'b'.code, grinCode), floatArrayOf(10f, 40f, 30f))
        assertEquals(1, lines(grin + grin, 60f, widths))
        assertEquals(2, lines(grin + grin, 59f, widths))
    }

    @Test
    fun a_character_never_measured_counts_as_the_widest() {
        val widths = LetterWidths(intArrayOf('a'.code, 'b'.code), floatArrayOf(10f, 30f))
        assertEquals(30f, widths.of('z'.code))
        assertEquals(10f, widths.of('a'.code))
        assertEquals(30f, widths.of(grinCode))
    }

    @Test
    fun distinct_lists_each_character_once_in_order() {
        assertContentEquals(intArrayOf('a'.code, 'b'.code, grinCode), ScreenLines.distinct("baab$grin$grin"))
        assertContentEquals(IntArray(0), ScreenLines.distinct(""))
    }

    /**
     * Characters are measured one per line in a single layout, so any that would start a line of its own
     * is left out (it counts as the widest) — or every width after it would belong to the wrong character.
     */
    @Test
    fun characters_that_break_a_line_are_not_measured() {
        val breaking = intArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)
        assertContentEquals(intArrayOf('a'.code, grinCode), ScreenLines.measurable(breaking + intArrayOf('a'.code, grinCode)))
    }

    @Test
    fun a_code_point_is_written_back_as_its_text() {
        assertEquals("a", ScreenLines.text('a'.code))
        assertEquals(grin, ScreenLines.text(grinCode))
    }

    /** Never the characters: the table's printout names nothing of the file. */
    @Test
    fun the_width_table_prints_no_characters() {
        assertEquals("LetterWidths", LetterWidths(intArrayOf('q'.code), floatArrayOf(1f)).toString())
    }

    @Test
    fun heights_are_the_first_line_and_each_line_after() {
        val text = TextDecoding.decode("aa\n\naaaa aaaa".encodeToByteArray()) as DecodedText.Text
        val rows = TextReading.rows(text)
        assertContentEquals(intArrayOf(100, 100, 180), ScreenLines.heights(rows, 50f, mono(), firstLinePx = 100, nextLinePx = 80))
    }
}
