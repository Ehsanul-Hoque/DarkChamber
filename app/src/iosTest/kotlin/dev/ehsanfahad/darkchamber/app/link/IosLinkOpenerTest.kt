package dev.ehsanfahad.darkchamber.app.link

import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpened
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS opener with the platform call swapped out: `UIApplication` is not reached from the simulator
 * test run. A non-web address never gets to the call; a web one does, as written, and the call's
 * answer is what the opener reports.
 */
class IosLinkOpenerTest {
    private val reached = mutableListOf<String>()

    private fun opener(answer: Boolean) = IosLinkOpener { url: NSURL, done: (Boolean) -> Unit ->
        reached += url.absoluteString.orEmpty()
        done(answer)
    }

    @Test
    fun a_non_web_address_never_reaches_the_platform() = runTest {
        val opener = opener(answer = true)

        listOf("javascript:alert(1)", "file:///x", "https://", " https://x").forEach {
            assertEquals(LinkOpened.NotOpened, opener.open(it), it)
        }
        assertEquals(emptyList(), reached)
    }

    @Test
    fun a_web_address_is_handed_over_as_written_and_the_answer_reported() = runTest {
        assertEquals(LinkOpened.Opened, opener(answer = true).open("https://Example.com/a%20b?q=1#top"))
        assertEquals(LinkOpened.NotOpened, opener(answer = false).open("https://example.com"))
        assertEquals(listOf("https://Example.com/a%20b?q=1#top", "https://example.com"), reached)
    }
}
