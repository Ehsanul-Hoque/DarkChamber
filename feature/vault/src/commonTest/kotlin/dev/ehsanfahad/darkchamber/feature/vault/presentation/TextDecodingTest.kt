package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Reading a verified file's bytes as text. Only what decodes exactly is text: a file that is not
 * valid in the encoding it claims is shown as not text, never with substitute characters standing
 * in for what could not be read.
 */
class TextDecodingTest {

    private fun hex(s: String): ByteArray =
        s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun lines(bytes: ByteArray): List<String>? =
        when (val d = TextDecoding.decode(bytes)) {
            is DecodedText.Text -> List(d.lineCount) { d.line(it) }
            DecodedText.NotText, DecodedText.TooManyLines -> null
        }

    // --- UTF-8 ---

    @Test
    fun utf8_without_a_byte_order_mark_is_text() {
        assertEquals(listOf("hello"), lines("hello".encodeToByteArray()))
    }

    @Test
    fun a_utf8_byte_order_mark_is_read_and_not_shown() {
        assertEquals(listOf("hello"), lines(hex("EF BB BF") + "hello".encodeToByteArray()))
    }

    /** Only the whole mark is a mark: a character whose bytes merely begin the same way is text. */
    @Test
    fun a_character_starting_like_the_byte_order_mark_is_kept() {
        val fullwidthExclamation = Char(0xFF01).toString()
        assertEquals(listOf("${fullwidthExclamation}a"), lines("${fullwidthExclamation}a".encodeToByteArray()))
    }

    /** Text in any script comes back exactly: Bangla, and a character outside the basic plane. */
    @Test
    fun text_in_any_script_comes_back_exactly() {
        // A grinning face, U+1F600, as its surrogate pair.
        val outsideTheBasicPlane = Char(0xD83D).toString() + Char(0xDE00)
        val text = "বাংলা লেখা — emoji $outsideTheBasicPlane"
        assertEquals(listOf(text), lines(text.encodeToByteArray()))
    }

    /** The file's own replacement character is content, kept — only a substituted one is refused. */
    @Test
    fun a_replacement_character_in_the_file_itself_is_kept() {
        val text = "a" + Char(0xFFFD) + "b"
        assertEquals(listOf(text), lines(text.encodeToByteArray()))
    }

    @Test
    fun a_lone_continuation_byte_is_not_text() {
        assertEquals(null, lines(hex("61 80 62")))
    }

    @Test
    fun an_overlong_form_is_not_text() {
        assertEquals(null, lines(hex("61 C0 AF 62")), "'/' in two bytes")
        assertEquals(null, lines(hex("61 E0 80 AF 62")), "'/' in three bytes")
    }

    @Test
    fun a_sequence_cut_short_is_not_text() {
        assertEquals(null, lines(hex("61 E0 A4")), "at the end")
        assertEquals(null, lines(hex("61 E0 A4 62")), "in the middle")
    }

    @Test
    fun an_encoded_surrogate_or_a_code_point_past_the_last_is_not_text() {
        assertEquals(null, lines(hex("61 ED A0 80 62")), "a surrogate written as UTF-8")
        assertEquals(null, lines(hex("61 F4 90 80 80 62")), "past U+10FFFF")
    }

    // --- Binary content ---

    /** A NUL is valid UTF-8 but no text file holds one; binary files are full of them. */
    @Test
    fun a_nul_anywhere_is_not_text() {
        assertEquals(null, lines(hex("00 61 62")), "first")
        assertEquals(null, lines(hex("61 00 62")), "middle")
        assertEquals(null, lines(hex("61 62 00")), "last")
        assertEquals(null, lines(hex("EF BB BF 00")), "right after a byte-order mark")
    }

    // --- UTF-16 ---

    /** [text] as UTF-16 code units, low byte first when [littleEndian]. */
    private fun utf16(text: String, littleEndian: Boolean): ByteArray =
        text.flatMap { c ->
            val hi = (c.code shr 8).toByte()
            val lo = (c.code and 0xFF).toByte()
            if (littleEndian) listOf(lo, hi) else listOf(hi, lo)
        }.toByteArray()

    private val leMark = hex("FF FE")
    private val beMark = hex("FE FF")

    @Test
    fun utf16_with_either_byte_order_mark_is_text() {
        val text = "বাংলা and " + Char(0xD83D) + Char(0xDE00)
        assertEquals(listOf(text), lines(leMark + utf16(text, littleEndian = true)), "little-endian")
        assertEquals(listOf(text), lines(beMark + utf16(text, littleEndian = false)), "big-endian")
    }

    /** The mark is how the byte order is known; read the other way, the same bytes are other words. */
    @Test
    fun the_byte_order_mark_decides_the_byte_order() {
        assertEquals(listOf("hi"), lines(hex("FF FE 68 00 69 00")))
        assertEquals(listOf("hi"), lines(hex("FE FF 00 68 00 69")))
        assertEquals(listOf(Char(0x6800).toString() + Char(0x6900)), lines(hex("FE FF 68 00 69 00")))
    }

    /** Without a mark the byte order is a guess, and the zero bytes of Latin text make it binary. */
    @Test
    fun utf16_without_a_byte_order_mark_is_not_text() {
        assertEquals(null, lines(utf16("hello", littleEndian = true)))
        assertEquals(null, lines(utf16("hello", littleEndian = false)))
    }

    @Test
    fun a_surrogate_without_its_partner_is_not_text() {
        assertEquals(null, lines(hex("FF FE 3D D8 61 00")), "a high surrogate before a letter")
        assertEquals(null, lines(hex("FF FE 61 00 3D D8")), "a high surrogate at the end")
        assertEquals(null, lines(hex("FF FE 00 DE 61 00")), "a low surrogate first")
        assertEquals(null, lines(hex("FE FF D8 3D D8 3D DE 00")), "two high surrogates")
    }

    @Test
    fun an_odd_number_of_bytes_after_the_mark_is_not_text() {
        assertEquals(null, lines(hex("FF FE 68 00 69")))
        assertEquals(null, lines(hex("FE FF 00")))
    }

    @Test
    fun a_nul_in_utf16_is_not_text() {
        assertEquals(null, lines(hex("FF FE 68 00 00 00 69 00")))
        assertEquals(null, lines(hex("FF FE 00 00 68 00 00 00")), "UTF-32 little-endian, which starts like UTF-16")
    }

    // --- Lines ---

    private fun linesOf(text: String) = lines(text.encodeToByteArray())

    @Test
    fun lines_end_at_lf_crlf_or_cr_and_the_ending_is_not_shown() {
        assertEquals(listOf("a", "b"), linesOf("a\nb"))
        assertEquals(listOf("a", "b"), linesOf("a\r\nb"))
        assertEquals(listOf("a", "b"), linesOf("a\rb"))
        assertEquals(listOf("a", "b", "c", "d"), linesOf("a\nb\r\nc\rd"), "mixed in one file")
    }

    /** A last line ending closes the last line; it does not open an empty one. A second one does. */
    @Test
    fun a_final_line_ending_adds_no_empty_line_but_two_add_one() {
        assertEquals(listOf("a"), linesOf("a\n"))
        assertEquals(listOf("a"), linesOf("a\r\n"))
        assertEquals(listOf("a"), linesOf("a\r"))
        assertEquals(listOf("a", ""), linesOf("a\n\n"))
        assertEquals(listOf("a", ""), linesOf("a\r\n\r\n"))
    }

    @Test
    fun empty_lines_are_kept_where_they_are() {
        assertEquals(listOf(""), linesOf("\n"))
        assertEquals(listOf("", ""), linesOf("\n\n"))
        assertEquals(listOf("", "a"), linesOf("\na"))
        assertEquals(listOf("a", "", "b"), linesOf("a\n\nb"))
        assertEquals(listOf("a", "", "b"), linesOf("a\r\rb"), "two CRs are two endings")
        assertEquals(listOf("a", "", "b"), linesOf("a\r\r\nb"), "a CR, then a CRLF")
        assertEquals(listOf("a", "", "b"), linesOf("a\n\rb"), "an LF then a CR is two endings, not one")
    }

    @Test
    fun an_empty_file_is_text_with_no_lines() {
        assertEquals(emptyList(), lines(ByteArray(0)))
    }

    @Test
    fun utf16_text_is_split_the_same_way() {
        assertEquals(listOf("a", "b", "c"), lines(leMark + utf16("a\r\nb\nc\r\n", littleEndian = true)))
    }

    /** A logged or printed value never carries the text: it is vault content. */
    @Test
    fun the_decoded_text_prints_as_a_count_only() {
        val decoded = TextDecoding.decode("secret line\nsecond".encodeToByteArray())
        assertEquals("Text(2 lines)", decoded.toString())
    }

    @Test
    fun a_byte_order_mark_alone_is_an_empty_text() {
        assertEquals(emptyList(), lines(hex("FF FE")))
        assertEquals(emptyList(), lines(hex("FE FF")))
        assertEquals(emptyList(), lines(hex("EF BB BF")))
    }

    // --- how many lines a page holds ---

    private fun decodeAtMost(text: String, maxLines: Int) = TextDecoding.decode(text.encodeToByteArray(), maxLines)

    /** Lines are counted as they are split: a final ending adds none, empty lines count, CRLF is one ending. */
    @Test
    fun text_of_more_lines_than_a_page_holds_is_refused() {
        assertEquals(3, assertIs<DecodedText.Text>(decodeAtMost("a\nb\nc", 3)).lineCount, "at the limit")
        assertEquals(3, assertIs<DecodedText.Text>(decodeAtMost("a\nb\nc\n", 3)).lineCount, "a final ending adds none")
        assertEquals(3, assertIs<DecodedText.Text>(decodeAtMost("a\r\nb\r\nc\r\n", 3)).lineCount, "CRLF is one ending")
        assertEquals(DecodedText.TooManyLines, decodeAtMost("a\nb\nc\nd", 3), "one over")
        assertEquals(DecodedText.TooManyLines, decodeAtMost("\n\n\n\n", 3), "empty lines count")
    }

    /** Not text stays not text, however many lines it would have had. */
    @Test
    fun bytes_that_are_not_text_are_not_text_whatever_their_lines() {
        // a, b NUL, c, d: four lines, one holding a NUL.
        val bytes = byteArrayOf(0x61, 0x0A, 0x62, 0x00, 0x0A, 0x63, 0x0A, 0x64)
        assertEquals(DecodedText.NotText, TextDecoding.decode(bytes, 3))
    }

    @Test
    fun a_page_holds_two_mebi_lines() {
        assertEquals(2_097_152, TEXT_MAX_LINES)
        assertEquals(TEXT_MAX_LINES, assertIs<DecodedText.Text>(TextDecoding.decode(ByteArray(TEXT_MAX_LINES) { 0x0A })).lineCount)
        assertEquals(DecodedText.TooManyLines, TextDecoding.decode(ByteArray(TEXT_MAX_LINES + 1) { 0x0A }))
    }

    /** A text made for a whole page (re-indented JSON) is held to the same limit. */
    @Test
    fun a_page_made_from_text_holds_no_more_lines_than_a_file() {
        assertEquals(listOf("a", "b", "c"), TextDecoding.pageLines("a\nb\nc\n", 3)?.let { t -> List(t.lineCount) { t.line(it) } })
        assertNull(TextDecoding.pageLines("a\nb\nc\nd", 3))
        assertEquals(TEXT_MAX_LINES, TextDecoding.pageLines("\n".repeat(TEXT_MAX_LINES))?.lineCount)
        assertNull(TextDecoding.pageLines("\n".repeat(TEXT_MAX_LINES + 1)))
    }
}
