package dev.ehsanfahad.darkchamber.feature.vault.presentation

/** What tapping a link in a text file may do. */
internal sealed interface LinkTarget {
    /**
     * A web address, opened only through the confirmation, which shows [address] exactly as the file
     * gives it and names [host] — the site it really reaches — on its own. Prints its length only: the
     * address is the file's content.
     */
    data class Web(val address: String, val host: String) : LinkTarget {
        override fun toString(): String = "Web(${address.length} chars)"
    }

    /** A `#section` link to a heading in the same document: the top-level block that holds it. */
    data class Section(val blockIndex: Int) : LinkTarget

    /** Anything else: shown as a link's text, but tapping it does nothing. */
    data object Inert : LinkTarget
}

internal object LinkRules {

    /**
     * What tapping a link to [address] may do. A web address opens — through the confirmation — only
     * when it starts with `http://` or `https://` (in any case) and names a site; nothing in it is
     * decoded or tidied, so the confirmation shows exactly what the file says. Every other scheme, an
     * address with none, and one padded or holding characters that change what is shown without
     * being seen, does nothing. So does one holding a backslash: browsers read it as a slash and iOS
     * escapes it, so where the site ends is not agreed and the confirmation could not name it truly.
     */
    fun target(address: String, sections: Map<String, Int>): LinkTarget {
        if (address.startsWith('#')) return section(address.substring(1), sections)
        if (address.any { it.isUnseen() || it == BACKSLASH }) return LinkTarget.Inert
        val rest = WEB_SCHEMES.firstOrNull { address.startsWith(it, ignoreCase = true) }?.let { address.substring(it.length) }
            ?: return LinkTarget.Inert
        val host = hostOf(rest)
        return if (host.isEmpty()) LinkTarget.Inert else LinkTarget.Web(address, host)
    }

    /**
     * Each heading's anchor, mapped to the top-level block that holds it — the heading itself, or the
     * quote or list it sits in — so a `#section` link can bring that block into view.
     */
    fun sections(document: MarkdownDocument): Map<String, Int> = buildMap {
        // Anchors are unique within a document (a repeat is numbered), so no anchor is put twice.
        document.blocks.forEachIndexed { index, block -> anchorsIn(block).forEach { put(it, index) } }
    }

    /** The anchors of every heading in [block], at any depth (the reader limits how deep that is). */
    private fun anchorsIn(block: MdBlock): List<String> =
        when (block) {
            is MdBlock.Heading -> listOf(block.anchor)
            is MdBlock.Quote -> block.blocks.flatMap(::anchorsIn)
            is MdBlock.ListBlock -> block.items.flatMap { item -> item.blocks.flatMap(::anchorsIn) }
            else -> emptyList()
        }

    /**
     * The heading a fragment names: as written, or percent-decoded as UTF-8 (how editors write
     * non-Latin anchors). Matched exactly — a fragment is case-sensitive, as a browser treats it.
     */
    private fun section(fragment: String, sections: Map<String, Int>): LinkTarget {
        val index = sections[fragment] ?: percentDecoded(fragment)?.let { sections[it] }
        return if (index == null) LinkTarget.Inert else LinkTarget.Section(index)
    }

    /**
     * [text] with each `%XX` read as a byte and the bytes as UTF-8; `null` without a `%`, or at an
     * escape that is not hex. Nothing stricter is needed: a short escape or broken UTF-8 can only make
     * a control character or U+FFFD, and no anchor holds either, so the lookup finds nothing.
     */
    private fun percentDecoded(text: String): String? {
        if ('%' !in text) return null
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                bytes += (text.substring(i + 1, minOf(i + 3, text.length)).toIntOrNull(16) ?: return null).toByte()
                i += 3
            } else {
                bytes += text[i].toString().encodeToByteArray().toList()
                i += 1
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    /**
     * The site in what follows `scheme://`: the authority (up to the first `/`, `?` or `#`), after any
     * user part (the last `@`), before any port — a bracketed address keeping its own colons.
     */
    private fun hostOf(rest: String): String {
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) rest else rest.substring(0, end)
        val hostAndPort = authority.substringAfterLast('@')
        return if (hostAndPort.startsWith('[')) {
            val close = hostAndPort.indexOf(']')
            if (close < 0) hostAndPort else hostAndPort.substring(0, close + 1)
        } else {
            hostAndPort.substringBefore(':')
        }
    }

    /** Controls, and the characters that reorder, join or hide text while showing nothing themselves. */
    private fun Char.isUnseen(): Boolean =
        code < 0x20 || code == 0x7F || code == 0x00AD || code == 0x061C || code == 0x180E ||
            code in 0x200B..0x200F || code in 0x2028..0x202E || code in 0x2066..0x2069 || code == 0xFEFF

    private val WEB_SCHEMES = listOf("http://", "https://")

    private val BACKSLASH = Char(0x5C)
}
