package dev.ehsanfahad.darkchamber.app.link

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The openers' own check, before an address reaches the platform: web addresses and nothing else. */
class WebAddressTest {

    @Test
    fun http_and_https_addresses_in_any_case_are_web_addresses() {
        listOf(
            "https://example.com",
            "http://example.com/a%20b?q=1#top",
            "HTTPS://EXAMPLE.COM",
            "Http://x",
        ).forEach { assertTrue(isWebAddress(it), it) }
    }

    @Test
    fun every_other_scheme_is_refused() {
        listOf(
            "javascript:alert(1)",
            "file:///etc/hosts",
            "intent://example.com#Intent;scheme=https;end",
            "content://media/1",
            "mailto:a@example.com",
            "httpx://example.com",
            "https:example.com",
            "example.com",
            "",
        ).forEach { assertFalse(isWebAddress(it), it) }
    }

    @Test
    fun a_scheme_with_nothing_after_it_is_refused() {
        assertFalse(isWebAddress("https://"))
        assertFalse(isWebAddress("http://"))
    }

    /** A parser may skip what a person never saw; such an address is refused rather than tidied. */
    @Test
    fun spaces_and_control_characters_are_refused() {
        listOf(
            " https://example.com",
            "https://example.com ",
            "https://exa mple.com",
            "https://example.com" + Char(0x0A) + "evil",
            "https://example.com" + Char(0x09),
            "https://example.com" + Char(0x00),
            "https://example.com" + Char(0x7F),
        ).forEach { assertFalse(isWebAddress(it), it.replace(Char(0x0A), '|')) }
    }

    /** Where a backslash ends the site is not agreed between browsers and iOS, so it is never handed over. */
    @Test
    fun a_backslash_anywhere_is_refused() {
        val bs = Char(0x5C)
        listOf("https://evil.example$bs@good.com/", "https://${bs}x", "https://example.com/a${bs}b").forEach {
            assertFalse(isWebAddress(it), it)
        }
    }
}
