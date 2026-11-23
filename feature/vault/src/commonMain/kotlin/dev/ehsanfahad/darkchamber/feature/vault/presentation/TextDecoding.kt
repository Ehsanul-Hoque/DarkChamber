package dev.ehsanfahad.darkchamber.feature.vault.presentation

/** A verified file's bytes, read as text — or found not to be text. */
internal sealed interface DecodedText {

    /**
     * The whole text, and where each line starts and ends within it, line endings excluded.
     *
     * One string and an index rather than one string per line: a file of millions of short lines
     * would otherwise cost far more as objects than its own size, and a screen draws only the lines
     * in view, cutting each out when it is drawn.
     */
    class Text internal constructor(private val text: String, private val bounds: IntArray) : DecodedText {
        val lineCount: Int get() = bounds.size / 2

        fun line(index: Int): String = text.substring(bounds[2 * index], bounds[2 * index + 1])

        /** Counts only: the text is vault content. */
        override fun toString(): String = "Text($lineCount lines)"
    }

    /** Bytes that are not text in an encoding this viewer reads. */
    data object NotText : DecodedText
}

internal object TextDecoding {

    /**
     * Reads [bytes] as UTF-16 when they start with its byte-order mark (either order), else as UTF-8
     * (a leading byte-order mark read and dropped). Anything not exactly valid is
     * [DecodedText.NotText]: a malformed sequence is reported, never replaced with a substitute
     * character, so what is shown is what the file holds. UTF-16 without a mark is not guessed at —
     * its zero bytes read as binary. The caller still owns [bytes] and zeroes them; this never keeps them.
     */
    fun decode(bytes: ByteArray): DecodedText {
        val text = when {
            startsWith(bytes, UTF16_LE_BOM) -> utf16(bytes, littleEndian = true)
            startsWith(bytes, UTF16_BE_BOM) -> utf16(bytes, littleEndian = false)
            else -> utf8(bytes)
        } ?: return DecodedText.NotText
        // Valid in every encoding read here, yet no text file holds one; binary files are full of them.
        if (text.indexOf(NUL) >= 0) return DecodedText.NotText
        return DecodedText.Text(text, lineBounds(text))
    }

    /**
     * Where each line starts and ends, endings excluded: LF, CRLF and a lone CR each end a line, so
     * a file from any platform reads the same. A final ending closes the last line rather than
     * opening an empty one. Counted first, then filled, so nothing is boxed or copied.
     */
    private fun lineBounds(text: String): IntArray {
        val bounds = IntArray(2 * forEachLine(text) { _, _, _ -> })
        forEachLine(text) { index, start, end ->
            bounds[2 * index] = start
            bounds[2 * index + 1] = end
        }
        return bounds
    }

    /** Calls [line] for each line in order; answers how many there were. */
    private inline fun forEachLine(text: String, line: (index: Int, start: Int, end: Int) -> Unit): Int {
        var count = 0
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == LF || c == CR) {
                line(count++, start, i)
                i += if (c == CR && i + 1 < text.length && text[i + 1] == LF) 2 else 1
                start = i
            } else {
                i += 1
            }
        }
        if (start < text.length) line(count++, start, text.length)
        return count
    }

    private fun utf8(bytes: ByteArray): String? {
        val start = if (startsWith(bytes, UTF8_BOM)) UTF8_BOM.size else 0
        return try {
            bytes.decodeToString(startIndex = start, endIndex = bytes.size, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * The code units after the two-byte mark, or `null` for an odd byte count or a surrogate without
     * its partner. The scratch array holds the text too, so it is zeroed once the string is made.
     */
    private fun utf16(bytes: ByteArray, littleEndian: Boolean): String? {
        val body = bytes.size - 2
        if (body % 2 != 0) return null
        val units = CharArray(body / 2)
        try {
            for (i in units.indices) {
                val first = bytes[2 + 2 * i].toInt() and 0xFF
                val second = bytes[3 + 2 * i].toInt() and 0xFF
                units[i] = Char(if (littleEndian) (second shl 8) or first else (first shl 8) or second)
            }
            var i = 0
            while (i < units.size) {
                val c = units[i]
                when {
                    c.isHighSurrogate() -> {
                        if (i + 1 >= units.size || !units[i + 1].isLowSurrogate()) return null
                        i += 2
                    }
                    c.isLowSurrogate() -> return null
                    else -> i += 1
                }
            }
            return units.concatToString()
        } finally {
            units.fill(NUL)
        }
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private val NUL = Char(0)
    private const val LF = '\n'
    private const val CR = '\r'

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
