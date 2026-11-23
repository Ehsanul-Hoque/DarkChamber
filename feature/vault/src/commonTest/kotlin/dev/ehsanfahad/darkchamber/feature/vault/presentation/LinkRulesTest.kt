package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a link in a text file may do. Only a web address opens, and only through the confirmation;
 * everything else is a link's text that does nothing — a file in the vault must not be able to start
 * another app, read a local file, or run script by being tapped.
 */
class LinkRulesTest {

    private fun target(address: String, sections: Map<String, Int> = emptyMap()) = LinkRules.target(address, sections)

    private fun web(address: String, host: String) = LinkTarget.Web(address, host)

    // --- Sections of the same document ---

    private fun sections(markdown: String) = LinkRules.sections(MarkdownModel.from(markdown)!!)

    @Test
    fun every_heading_is_a_section_at_its_top_level_block() {
        assertEquals(mapOf("intro" to 0, "next-steps" to 2), sections("# Intro\n\ntext\n\n## Next steps\n"))
    }

    /** A heading inside a quote or a list is reached at the block that holds it. */
    @Test
    fun a_nested_heading_is_reached_at_its_outer_block() {
        assertEquals(mapOf("top" to 0, "inner" to 1, "in-list" to 2), sections("# Top\n\n> # Inner\n\n- # In list\n"))
    }

    @Test
    fun repeated_titles_are_each_their_own_section() {
        assertEquals(mapOf("a" to 0, "a-1" to 1), sections("# a\n# a\n"))
    }

    @Test
    fun a_section_link_jumps_to_its_heading() {
        val sections = sections("# Intro\n\ntext\n\n## Next steps\n")
        assertEquals(LinkTarget.Section(2), target("#next-steps", sections))
        assertEquals(LinkTarget.Section(0), target("#intro", sections))
    }

    /** Anchors match as written: a fragment is case-sensitive, as a browser treats it. */
    @Test
    fun a_section_link_to_no_heading_does_nothing() {
        val sections = sections("# Intro\n")
        for (address in listOf("#nope", "#", "#Intro", "# intro", "intro")) assertEquals(LinkTarget.Inert, target(address, sections), address)
    }

    /** A link may spell its anchor in percent-encoded UTF-8, as editors write non-Latin ones. */
    @Test
    fun a_percent_encoded_section_link_finds_its_heading() {
        val sections = sections("# বাংলা লেখা\n")
        val encoded = "বাংলা-লেখা".encodeToByteArray().joinToString("") { "%" + (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
        assertEquals(LinkTarget.Section(0), target("#$encoded", sections))
        assertEquals(LinkTarget.Section(0), target("#" + encoded.lowercase(), sections), "hex digits in either case")
        assertEquals(LinkTarget.Section(0), target("#বাংলা-লেখা", sections), "or as written")
    }

    @Test
    fun a_broken_percent_encoding_does_nothing() {
        val sections = sections("# a\n")
        for (address in listOf("#%ZZ", "#%6", "#%", "#%FF%FE")) assertEquals(LinkTarget.Inert, target(address, sections), address)
        assertEquals(LinkTarget.Section(0), target("#%61", sections), "an encoded ASCII letter")
    }

    // --- Web addresses ---

    @Test
    fun http_and_https_open_and_the_address_is_kept_exactly() {
        assertEquals(web("https://example.com/a?b=c#d", "example.com"), target("https://example.com/a?b=c#d"))
        assertEquals(web("http://example.com", "example.com"), target("http://example.com"))
        assertEquals(web("HTTPS://Example.COM/X", "Example.COM"), target("HTTPS://Example.COM/X"), "the scheme in any case; nothing re-cased")
    }

    /** Nothing is decoded or tidied: the confirmation shows what the file says. */
    @Test
    fun an_address_is_never_decoded_or_tidied() {
        assertEquals(web("https://xn--bcher-kva.example/%41%20b", "xn--bcher-kva.example"), target("https://xn--bcher-kva.example/%41%20b"))
        assertEquals(web("https://example.com/my uri", "example.com"), target("https://example.com/my uri"))
    }

    /** The site is what follows any user part and comes before a port, a path, a query or a fragment. */
    @Test
    fun the_host_is_the_site_the_address_really_reaches() {
        assertEquals("evil.example", (target("https://google.com@evil.example/login") as LinkTarget.Web).host)
        assertEquals("evil.example", (target("https://a:b@c@evil.example") as LinkTarget.Web).host, "the last @ before the path")
        assertEquals("example.com", (target("https://example.com:8443/x") as LinkTarget.Web).host)
        assertEquals("example.com", (target("https://example.com?q") as LinkTarget.Web).host)
        assertEquals("example.com", (target("https://example.com#f") as LinkTarget.Web).host)
        assertEquals("[2001:db8::1]", (target("http://[2001:db8::1]:80/") as LinkTarget.Web).host, "a bracketed address keeps its colons")
        assertEquals("example.com", (target("https://user@example.com/a@b") as LinkTarget.Web).host, "an @ in the path is the path's")
    }

    @Test
    fun a_web_address_with_no_site_does_not_open() {
        for (address in listOf("http://", "https://", "http:///path", "https://user@/x", "https://:443/", "http:example.com", "https:/example.com")) {
            assertEquals(LinkTarget.Inert, target(address), address)
        }
    }

    /** Another app, a local file, script, a phone call or mail — none of it is the browser's, and none opens. */
    @Test
    fun every_other_scheme_does_nothing() {
        for (
            address in listOf(
                "javascript:alert(1)", "intent://x#Intent;scheme=http;end", "file:///sdcard/x", "content://media/1",
                "data:text/html,<b>x</b>", "mailto:a@b.c", "tel:123", "sms:123", "market://details?id=x",
                "dc-vault://open", "ftp://example.com", "httpx://example.com", "https-evil://example.com", "ws://example.com",
            )
        ) {
            assertEquals(LinkTarget.Inert, target(address), address)
        }
    }

    @Test
    fun an_address_without_a_scheme_does_nothing() {
        for (address in listOf("", "example.com", "//example.com", "/path", "notes.md", "../x", "?q")) {
            assertEquals(LinkTarget.Inert, target(address), address)
        }
    }

    /** The scheme must start the address: padding could hide another scheme in front of it. */
    @Test
    fun a_padded_address_does_nothing() {
        val wide = " ".repeat(8) + "https://example.com"
        for (address in listOf(" https://example.com", wide, "\thttps://example.com", " javascript:alert(1)", "\nhttps://example.com")) {
            assertEquals(LinkTarget.Inert, target(address), address)
        }
    }

    /**
     * Characters that change what is shown without being seen — direction marks, zero-width characters,
     * controls — could make the confirmation show another address than the one opened, so none opens.
     */
    @Test
    fun an_address_holding_unseen_characters_does_nothing() {
        val unseen = listOf(
            0x00, 0x09, 0x0A, 0x1F, 0x7F, 0x00AD, 0x061C, 0x180E, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F,
            0x2028, 0x2029, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF,
        )
        for (code in unseen) {
            val address = "https://example.com/a" + Char(code) + "b"
            assertEquals(LinkTarget.Inert, target(address), "U+" + code.toString(16).padStart(4, '0'))
        }
        assertEquals(LinkTarget.Inert, target("https://exa" + Char(0x202E) + "moc.elpmax"), "in the host too")
    }

    /** Visible characters of any script are not unseen: an address in Bangla opens. */
    @Test
    fun visible_characters_of_any_script_are_kept() {
        assertEquals(web("https://example.com/বাংলা", "example.com"), target("https://example.com/বাংলা"))
        // The neighbours of each unseen range are ordinary characters.
        for (code in listOf(0x20, 0x7E, 0x00A0, 0x2010, 0x2027, 0x202F, 0x2065, 0x206A, 0xFEFE)) {
            val address = "https://example.com/a" + Char(code) + "b"
            assertEquals(web(address, "example.com"), target(address), "U+" + code.toString(16).padStart(4, '0'))
        }
    }
}
