package dev.ehsanfahad.darkchamber.feature.vault.presentation

/** The most characters an indented JSON view may hold; past it the file is shown as stored. */
internal const val JSON_INDENT_CAP: Int = 24 * 1024 * 1024

internal object JsonIndent {
    /** What re-indenting a JSON text came to. */
    sealed interface Result {
        /** The same text, its whitespace outside strings replaced by an indented layout. */
        class Indented(
            val text: String,
        ) : Result {
            /** Length only: the text is vault content. */
            override fun toString(): String = "Indented(${text.length} chars)"
        }

        /** Not valid JSON: shown as stored, and the page says it could not be formatted. */
        data object Invalid : Result

        /** Valid, but indenting it would take more than the cap — deep nesting repeats its indent on every line. */
        data object TooLarge : Result
    }

    /**
     * Re-indents [text] for display, two spaces a level, changing nothing but whitespace outside
     * strings: every token — each string, number and literal — is copied exactly as stored, so
     * duplicate keys, key order, number spellings and escapes all survive. One pass, no tree, an
     * explicit stack rather than recursion (nesting depth is the file's choice, not ours).
     *
     * Stops as [Result.TooLarge] once the output passes [cap] characters, and as [Result.Invalid] at
     * the first thing JSON does not allow.
     */
    fun indent(
        text: String,
        cap: Int = JSON_INDENT_CAP,
    ): Result {
        val out = StringBuilder()
        val open = StringBuilder() // The containers open at this point, innermost last.
        var expect = Expect.Value
        var i = 0
        while (true) {
            // Measured before every token and once more at the end, so the last one written counts too.
            if (out.length > cap) return Result.TooLarge
            i = skipWhitespace(text, i)
            if (i == text.length) return if (expect == Expect.End) Result.Indented(out.toString()) else Result.Invalid
            val c = text[i]
            when (expect) {
                Expect.End -> {
                    return Result.Invalid
                }

                Expect.Colon -> {
                    if (c != ':') return Result.Invalid
                    out.append(": ")
                    i += 1
                    expect = Expect.Value
                }

                Expect.CommaOrClose -> {
                    when (c) {
                        ',' -> {
                            out.append(',')
                            newLine(out, open.length)
                            i += 1
                            expect = if (open.last() == '{') Expect.Key else Expect.Value
                        }

                        '}', ']' -> {
                            if (c != closerOf(open.last())) return Result.Invalid
                            open.setLength(open.length - 1)
                            newLine(out, open.length)
                            out.append(c)
                            i += 1
                            expect = afterValue(open)
                        }

                        else -> {
                            return Result.Invalid
                        }
                    }
                }

                Expect.Key -> {
                    if (c != '"') return Result.Invalid
                    val end = stringEnd(text, i) ?: return Result.Invalid
                    out.append(text, i, end)
                    i = end
                    expect = Expect.Colon
                }

                Expect.Value -> {
                    when (c) {
                        '{', '[' -> {
                            val next = skipWhitespace(text, i + 1)
                            if (next < text.length && text[next] == closerOf(c)) {
                                out.append(c).append(closerOf(c))
                                i = next + 1
                                expect = afterValue(open)
                            } else {
                                open.append(c)
                                out.append(c)
                                newLine(out, open.length)
                                i += 1
                                expect = if (c == '{') Expect.Key else Expect.Value
                            }
                        }

                        '"' -> {
                            val end = stringEnd(text, i) ?: return Result.Invalid
                            out.append(text, i, end)
                            i = end
                            expect = afterValue(open)
                        }

                        else -> {
                            val end = scalarEnd(text, i) ?: return Result.Invalid
                            out.append(text, i, end)
                            i = end
                            expect = afterValue(open)
                        }
                    }
                }
            }
        }
    }

    private enum class Expect { Value, Key, Colon, CommaOrClose, End }

    private fun afterValue(open: StringBuilder): Expect = if (open.isEmpty()) Expect.End else Expect.CommaOrClose

    private fun closerOf(opener: Char): Char = if (opener == '{') '}' else ']'

    private fun newLine(
        out: StringBuilder,
        depth: Int,
    ) {
        out.append('\n')
        repeat(depth) { out.append(INDENT) }
    }

    /** JSON's four whitespace characters, and no others. */
    private fun skipWhitespace(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\n' || text[i] == '\r')) i += 1
        return i
    }

    /**
     * Just past the string starting at [start] (on its opening quote), or `null` if it never closes or
     * holds what JSON does not allow: a raw control character, or an escape other than the eight
     * one-letter ones and backslash-u with four hex digits.
     */
    private fun stringEnd(
        text: String,
        start: Int,
    ): Int? {
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    return i + 1
                }

                c.code < 0x20 -> {
                    return null
                }

                c == BACKSLASH -> {
                    val escape = text.getOrNull(i + 1) ?: return null
                    i +=
                        when (escape) {
                            '"', BACKSLASH, '/', 'b', 'f', 'n', 'r', 't' -> {
                                2
                            }

                            'u' -> {
                                if ((i + 2..i + 5).all {
                                        it < text.length && text[it].isHexDigit()
                                    }
                                ) {
                                    6
                                } else {
                                    return null
                                }
                            }

                            else -> {
                                return null
                            }
                        }
                }

                else -> {
                    i += 1
                }
            }
        }
        return null
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * Just past a literal or a number starting at [start], or `null` if none starts there. A number
     * follows JSON's grammar exactly — `-`, then `0` or a digit run not starting with `0`, then an
     * optional fraction and exponent — and whatever follows it is left to the caller to judge.
     */
    private fun scalarEnd(
        text: String,
        start: Int,
    ): Int? {
        for (literal in LITERALS) {
            if (text.startsWith(literal, start)) return start + literal.length
        }
        var i = start
        if (text.getOrNull(i) == '-') i += 1
        i =
            when {
                text.getOrNull(i) == '0' -> i + 1
                text.getOrNull(i) in '1'..'9' -> digitsEnd(text, i)
                else -> return null
            }
        if (text.getOrNull(i) == '.') {
            val end = digitsEnd(text, i + 1)
            if (end == i + 1) return null
            i = end
        }
        if (text.getOrNull(i) == 'e' || text.getOrNull(i) == 'E') {
            i += 1
            if (text.getOrNull(i) == '+' || text.getOrNull(i) == '-') i += 1
            val end = digitsEnd(text, i)
            if (end == i) return null
            i = end
        }
        return i
    }

    private fun digitsEnd(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length && text[i] in '0'..'9') i += 1
        return i
    }

    private const val INDENT = "  "
    private val BACKSLASH = Char(0x5C)
    private val LITERALS = listOf("true", "false", "null")
}
