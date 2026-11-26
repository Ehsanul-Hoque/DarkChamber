package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Re-indenting JSON for display. The view must show the file, not a re-reading of it: only whitespace
 * outside strings may change, so every expected output here is written by hand, and a second route
 * (stripping that whitespace) checks each one against its input.
 */
class JsonIndentTest {
    /** A backslash, built rather than typed, so no escape sequence is ever written into this file. */
    private val bs = Char(0x5C)

    private fun indented(json: String): String {
        val result = JsonIndent.indent(json)
        assertIs<JsonIndent.Result.Indented>(result, "for: $json")
        return result.text
    }

    private fun lines(vararg lines: String) = lines.joinToString("\n")

    // --- Scalars and flat containers ---

    @Test
    fun a_scalar_alone_is_itself() {
        assertEquals("1", indented("1"))
        assertEquals("\"a\"", indented("\"a\""))
        assertEquals("true", indented("true"))
        assertEquals("false", indented("false"))
        assertEquals("null", indented("null"))
        assertEquals("null", indented("  null \n"), "surrounding whitespace is not content")
    }

    @Test
    fun an_object_puts_each_member_on_its_own_line() {
        assertEquals(
            lines("{", """  "a": 1,""", """  "b": "x"""", "}"),
            indented("""{"a":1,"b":"x"}"""),
        )
    }

    @Test
    fun an_array_puts_each_element_on_its_own_line() {
        assertEquals(lines("[", "  1,", "  2,", "  3", "]"), indented("[1,2,3]"))
    }

    @Test
    fun an_empty_object_or_array_stays_on_one_line() {
        assertEquals("{}", indented("{}"))
        assertEquals("[]", indented("[]"))
        assertEquals("{}", indented("{ \n\t }"), "whitespace inside an empty container is not content")
        assertEquals(lines("[", "  {},", "  []", "]"), indented("[{},[]]"))
    }

    @Test
    fun whitespace_between_tokens_is_replaced_not_kept() {
        assertEquals(
            lines("{", """  "a": 1""", "}"),
            indented("{\r\n\t\"a\"  :\n   1  \r}"),
        )
    }

    // --- Content kept exactly ---

    @Test
    fun nesting_indents_two_spaces_a_level() {
        assertEquals(
            lines(
                "{",
                """  "a": {""",
                """    "b": [""",
                "      1,",
                "      {",
                """        "c": null""",
                "      }",
                "    ]",
                "  },",
                """  "d": []""",
                "}",
            ),
            indented("""{"a":{"b":[1,{"c":null}]},"d":[]}"""),
        )
    }

    /** Punctuation inside a string is the string's, never structure — and its spaces are content. */
    @Test
    fun a_string_holding_json_punctuation_is_copied_as_it_is() {
        val json = "\"{ [a, b]: c } , $bs\"q$bs\" $bs$bs end\""
        assertEquals(lines("[", "  $json", "]"), indented("[$json]"))
        assertEquals(lines("{", "  $json: $json", "}"), indented("{$json:$json}"))
    }

    @Test
    fun escapes_are_kept_as_written_never_decoded() {
        val unicode = "\"${bs}u0041${bs}u00e9${bs}uD83D${bs}uDE00\""
        val short = "\"$bs\"$bs$bs$bs/${bs}b${bs}f${bs}n${bs}r${bs}t\""
        assertEquals(lines("[", "  $unicode,", "  $short", "]"), indented("[$unicode,$short]"))
    }

    /** A parser would keep one of them; the file has both, so the view shows both, in order. */
    @Test
    fun duplicate_keys_and_key_order_are_kept() {
        assertEquals(
            lines("{", """  "b": 1,""", """  "a": 2,""", """  "b": 3""", "}"),
            indented("""{"b":1,"a":2,"b":3}"""),
        )
    }

    /** A number is shown as spelled: no parse could keep `1.50`, `-0` or `1E+10` apart from their values. */
    @Test
    fun numbers_keep_their_spelling() {
        val numbers = listOf("0", "-0", "1.50", "1e10", "1E+10", "-2.5e-3", "123456789012345678901234567890")
        assertEquals(
            lines("[", numbers.joinToString(",\n") { "  $it" }, "]"),
            indented(numbers.joinToString(",", "[", "]")),
        )
    }

    @Test
    fun already_indented_json_comes_back_unchanged() {
        val pretty = lines("{", """  "a": [""", "    1,", "    true", "  ],", """  "b": {}""", "}")
        assertEquals(pretty, indented(pretty))
    }

    // --- Not JSON: shown as stored, with a note ---

    private fun assertInvalid(vararg inputs: String) {
        for (json in inputs) assertEquals(JsonIndent.Result.Invalid, JsonIndent.indent(json), "for: $json")
    }

    @Test
    fun nothing_at_all_is_not_json() {
        assertInvalid("", "  \n\t ")
    }

    @Test
    fun unbalanced_or_mismatched_brackets_are_not_json() {
        assertInvalid("[1", "{\"a\":1", "]", "}", "[1]]", "[1}", "{\"a\":1]", "[[]")
    }

    @Test
    fun anything_after_the_value_is_not_json() {
        assertInvalid("1 2", "{} x", "[]]", "\"a\" \"b\"", "null,")
    }

    @Test
    fun a_comment_is_not_json() {
        assertInvalid("// note\n{}", "{/* note */}", "[1 # note\n]")
    }

    @Test
    fun a_misplaced_comma_colon_or_member_is_not_json() {
        assertInvalid(
            "[1,]",
            "{\"a\":1,}",
            "[,1]",
            "[1,,2]",
            "{,}",
            "{\"a\"}",
            "{\"a\":}",
            "{\"a\" 1}",
            "{\"a\"::1}",
            "{\"a\";1}",
            "[1:2]",
            "{\"a\":1 \"b\":2}",
        )
    }

    @Test
    fun a_key_that_is_not_a_string_is_not_json() {
        assertInvalid("{a:1}", "{1:2}", "{null:1}", "{[]:1}")
        // A key that only ends like a string: read from its quote, it would pass.
        assertInvalid("{a\":1}")
    }

    @Test
    fun an_unterminated_string_is_not_json() {
        assertInvalid("\"abc", "[\"abc]", "\"abc$bs\"", "\"abc$bs")
    }

    @Test
    fun single_quotes_are_not_json() {
        assertInvalid("'a'", "{'a':1}")
    }

    @Test
    fun a_number_json_does_not_allow_is_not_json() {
        assertInvalid("01", "-01", "1.", ".5", "+1", "1e", "1e+", "-", "1.5.3", "0x10", "Infinity", "NaN", "1-2", "--1")
    }

    @Test
    fun a_literal_in_any_other_spelling_is_not_json() {
        assertInvalid("True", "NULL", "nul", "nulls", "truefalse", "undefined")
    }

    @Test
    fun an_escape_json_does_not_allow_is_not_json() {
        assertInvalid("\"${bs}x\"", "\"${bs}u12\"", "\"${bs}u12G4\"", "\"${bs}u123G\"", "\"${bs}U0041\"", "\"$bs'\"")
    }

    /** A raw control character must be escaped inside a string; a raw tab or line break is not JSON. */
    @Test
    fun a_raw_control_character_inside_a_string_is_not_json() {
        assertInvalid("\"a\tb\"", "\"a\nb\"", "\"a" + Char(0x01) + "b\"", "\"a" + Char(0x1F) + "b\"")
    }

    /** Only space, tab, LF and CR separate tokens; a form feed or a no-break space is not JSON. */
    @Test
    fun whitespace_json_does_not_name_is_not_json() {
        assertInvalid("[1," + Char(0x0C) + "2]", "[1," + Char(0xA0) + "2]")
    }

    /** The edges of what is allowed, so the refusals above are not refusing too much. */
    @Test
    fun the_edges_of_the_grammar_are_json() {
        assertEquals(
            "\"" + Char(0x7F) + Char(0x20) + "\"",
            indented("\"" + Char(0x7F) + Char(0x20) + "\""),
            "DEL and space are allowed raw",
        )
        assertEquals("\"${bs}u00aF\"", indented("\"${bs}u00aF\""), "hex digits in either case")
        assertEquals("-0.0e-0", indented("-0.0e-0"))
        assertEquals("0", indented("0"))
    }

    // --- The output cap ---

    /** `[1,2]` indents to 12 characters: allowed at a cap of 12, refused at 11 — both sides of it. */
    @Test
    fun the_indented_view_may_be_as_long_as_the_cap_and_not_a_character_more() {
        val json = "[1,2]"
        assertEquals(12, indented(json).length)
        assertIs<JsonIndent.Result.Indented>(JsonIndent.indent(json, cap = 12))
        assertIs<JsonIndent.Result.Indented>(
            JsonIndent.indent("[1,2]  \n", cap = 12),
            "trailing whitespace adds nothing",
        )
        assertEquals(JsonIndent.Result.TooLarge, JsonIndent.indent(json, cap = 11))
    }

    /** The last character written counts too: a cap one short of the closing bracket refuses. */
    @Test
    fun the_closing_bracket_is_measured() {
        assertEquals(JsonIndent.Result.TooLarge, JsonIndent.indent("{\"a\":1}", cap = indented("{\"a\":1}").length - 1))
    }

    @Test
    fun the_cap_is_twenty_four_mebi_characters() {
        assertEquals(24 * 1024 * 1024, JSON_INDENT_CAP)
    }

    /**
     * Deep nesting repeats its indent on every line, so the view outgrows the file: 20 000 levels in
     * 40 KB would indent to about 800 million characters. It stops at the cap instead — and with no
     * recursion, the depth costs no stack.
     */
    @Test
    fun deep_nesting_stops_at_the_cap_rather_than_growing_without_bound() {
        val depth = 20_000
        val json = "[".repeat(depth) + "1" + "]".repeat(depth)
        assertEquals(JsonIndent.Result.TooLarge, JsonIndent.indent(json))
    }

    /** Within the cap, depth is no limit: 2 000 levels indent to a line per bracket, about 8 million characters. */
    @Test
    fun deep_nesting_within_the_cap_is_indented() {
        val depth = 2_000
        val text = indented("[".repeat(depth) + "1" + "]".repeat(depth))
        assertEquals(2 * depth + 1, text.count { it == '\n' } + 1)
        assertEquals(" ".repeat(2 * depth) + "1", text.lines()[depth])
    }

    // --- The second route ---

    /** Every whitespace character outside a string removed — what a re-indent may change and nothing else. */
    private fun withoutLayout(json: String): String {
        val out = StringBuilder()
        var inString = false
        var escaped = false
        for (c in json) {
            when {
                inString -> {
                    out.append(c)
                    if (escaped) {
                        escaped = false
                    } else if (c == bs) {
                        escaped = true
                    } else if (c == '"') {
                        inString = false
                    }
                }

                c == '"' -> {
                    out.append(c)
                    inString = true
                }

                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> {}

                else -> {
                    out.append(c)
                }
            }
        }
        return out.toString()
    }

    private val fixtures: List<String>
        get() =
            listOf(
                """{"a":{"b":[1,{"c":null}]},"d":[]}""",
                """[" { } [ ] , : ", "$bs"", "$bs$bs", "${bs}u007B"]""",
                """{"b":1,"a":2,"b":3}""",
                """[0,-0,1.50,1e10,1E+10,-2.5e-3]""",
                lines("{", """  "a": [""", "    1,", "    true", "  ],", """  "b": {}""", "}"),
                " \t\r\n [ [ [ ] ] , { \"k k\" : \"v${bs}tv\" } ] \n",
                "\"only a string with  spaces\"",
                "[[[[[[[[[[1]]]]]]]]]]",
            )

    @Test
    fun indenting_changes_nothing_but_whitespace_outside_strings() {
        for (json in fixtures) assertEquals(withoutLayout(json), withoutLayout(indented(json)), json)
    }

    @Test
    fun indenting_twice_is_indenting_once() {
        for (json in fixtures) assertEquals(indented(json), indented(indented(json)), json)
    }
}
