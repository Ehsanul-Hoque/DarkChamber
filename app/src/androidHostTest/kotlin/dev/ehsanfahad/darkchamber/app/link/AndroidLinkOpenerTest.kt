package dev.ehsanfahad.darkchamber.app.link

import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpened
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Android opener's own checks, before any intent exists. Starting the intent needs a real device
 * and is checked on one; here, an address that is not a web address — or no activity to start from —
 * never gets as far as asking for the activity or building the intent.
 */
class AndroidLinkOpenerTest {
    private var asked = 0

    @Test
    fun a_non_web_address_never_reaches_the_activity() = runTest {
        val opener = AndroidLinkOpener { asked++; null }

        listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///x", "https://", " https://x").forEach {
            assertEquals(LinkOpened.NotOpened, opener.open(it), it)
        }
        assertEquals(0, asked, "refused before the activity was asked for")
    }

    @Test
    fun with_no_activity_in_front_nothing_opens() = runTest {
        val opener = AndroidLinkOpener { asked++; null }

        assertEquals(LinkOpened.NotOpened, opener.open("https://example.com"))
        assertEquals(1, asked)
    }
}
