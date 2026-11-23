package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The words of the confirmation before a web link leaves the vault, written from what can go wrong:
 * the address leaves the app, the browser keeps what it keeps, the site may learn of the visit, and
 * the vault locks once the browser has the address. Nothing here may promise what the app cannot keep.
 */
class LinkDialogCopyTest {

    private val dialog: List<String>
        get() = listOf(
            ViewerFormat.linkTitle(), ViewerFormat.linkSite("example.com"), ViewerFormat.linkLeaves(),
            ViewerFormat.linkLocks(), ViewerFormat.linkOpen(), ViewerFormat.cancel(),
        )

    @Test
    fun the_dialog_asks_and_says_where_it_goes() {
        assertEquals("Open this link in your browser?", ViewerFormat.linkTitle())
        assertEquals("Site: evil.example", ViewerFormat.linkSite("evil.example"))
    }

    /** Each consequence the app cannot take back once the browser has the address. */
    @Test
    fun the_dialog_says_what_leaving_means() {
        assertEquals(
            "The address leaves the vault. Your browser may keep it in its history and sync it to your other devices, and the site can learn that it was opened.",
            ViewerFormat.linkLeaves(),
        )
        assertEquals("Opening it locks the vault.", ViewerFormat.linkLocks())
    }

    @Test
    fun the_buttons_say_what_they_do() {
        assertEquals("Open", ViewerFormat.linkOpen())
        assertEquals("Cancel", ViewerFormat.cancel())
    }

    /**
     * When nothing took the address, nothing left and nothing locked. The platform does not say why —
     * no app for web links, or this address refused — so it names no cause; it is the viewer's notice.
     */
    @Test
    fun the_notice_when_no_app_took_it() {
        assertEquals("No app opened this link.", ViewerFormat.linkNotOpened())
        assertEquals(ViewerFormat.linkNotOpened(), ViewerFormat.notice(ViewerNotice.LinkNotOpened))
    }

    /**
     * No word the app cannot stand behind: what the browser and the site do is theirs, so nothing is
     * called safe or private; the visit may be seen, so nothing says it will not be; and "will"
     * about the site would be false offline — it says "can".
     */
    @Test
    fun the_dialog_promises_nothing_the_app_cannot_keep() {
        val words = (dialog + ViewerFormat.linkNotOpened()).joinToString(" ").lowercase()
        for (claim in listOf("safe", "secure", "private", "encrypt", "protect", "anonym", "trust", "verified", "won't", "will not", "never", "will know")) {
            assertFalse(claim in words, "the dialog must not say \"$claim\"")
        }
    }
}
