package dev.ehsanfahad.darkchamber.app.link

import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpened
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Android opener's own checks and answers. Building and starting the intent needs a real device and
 * is checked on one; here the start is a stand-in, so what reaches it — and what the opener answers when
 * it throws — can be seen.
 */
class AndroidLinkOpenerTest {
    private var asked = 0
    private val started = mutableListOf<String>()

    private fun opener(start: (String) -> Unit = { started += it }) =
        AndroidLinkOpener {
            asked++
            start
        }

    @Test
    fun a_non_web_address_never_reaches_the_activity() =
        runTest {
            val opener =
                AndroidLinkOpener {
                    asked++
                    null
                }

            listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///x", "https://", " https://x").forEach {
                assertEquals(LinkOpened.NotOpened, opener.open(it), it)
            }
            assertEquals(0, asked, "refused before the activity was asked for")
        }

    @Test
    fun with_no_activity_in_front_nothing_opens() =
        runTest {
            val opener =
                AndroidLinkOpener {
                    asked++
                    null
                }

            assertEquals(LinkOpened.NotOpened, opener.open("https://example.com"))
            assertEquals(1, asked)
        }

    @Test
    fun a_web_address_is_started_as_written() =
        runTest {
            assertEquals(LinkOpened.Opened, opener().open("https://Example.com/A?b=C#D"))
            assertEquals(listOf("https://Example.com/A?b=C#D"), started)
        }

    /**
     * Android matches a link's scheme to the apps that open links exactly, in lower case, so `HTTPS://…`
     * would find no browser. The scheme alone is lowered — the rest, which the reader saw, is untouched.
     */
    @Test
    fun only_the_scheme_is_lowered() =
        runTest {
            val opener = opener()
            opener.open("HTTPS://Example.COM/Path")
            opener.open("Http://x.example/Y")
            assertEquals(listOf("https://Example.COM/Path", "http://x.example/Y"), started)
        }

    /**
     * Starting can fail in more ways than "no app": an app that will not be started by others, or the
     * system refusing the hand-over itself (an address too large to pass). Each means nothing opened.
     */
    @Test
    fun a_start_that_throws_means_nothing_opened() =
        runTest {
            assertEquals(
                LinkOpened.NotOpened,
                opener { throw SecurityException("not exported") }.open("https://example.com"),
            )
            assertEquals(
                LinkOpened.NotOpened,
                opener {
                    throw RuntimeException("transaction too large")
                }.open("https://example.com"),
            )
        }
}
