package dev.ehsanfahad.darkchamber.app.system

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The exemption must be released on every path. A handoff left open would exempt the vault from
 * auto-lock for the rest of the session — a security hole, not a stuck flag.
 */
class SystemUiHandoffTest {

    @Test
    fun starts_with_nothing_in_flight() {
        assertFalse(SystemUiHandoff().inFlight)
    }

    @Test
    fun a_handoff_is_in_flight_only_while_it_runs() = runTest {
        val handoff = SystemUiHandoff()

        handoff.during {
            assertTrue(handoff.inFlight, "the pick is outstanding")
        }

        assertFalse(handoff.inFlight, "returning must release the exemption")
    }

    @Test
    fun a_failed_handoff_releases_the_exemption() = runTest {
        val handoff = SystemUiHandoff()

        assertFailsWith<IllegalStateException> {
            handoff.during { error("the picker blew up") }
        }

        assertFalse(handoff.inFlight, "a throw must not leave the vault exempt from auto-lock")
    }

    @Test
    fun a_cancelled_handoff_releases_the_exemption() = runTest {
        val handoff = SystemUiHandoff()

        assertFailsWith<CancellationException> {
            handoff.during { throw CancellationException("the gallery scope died") }
        }

        assertFalse(handoff.inFlight, "cancellation must not leave the vault exempt from auto-lock")
    }

    @Test
    fun nested_handoffs_release_one_at_a_time() = runTest {
        val handoff = SystemUiHandoff()

        handoff.during {
            handoff.during {
                assertTrue(handoff.inFlight)
            }
            assertTrue(handoff.inFlight, "the outer pick is still outstanding")
        }

        assertFalse(handoff.inFlight)
    }

    @Test
    fun an_unmatched_end_cannot_drive_the_count_negative() {
        val handoff = SystemUiHandoff()

        handoff.end() // stray release, e.g. a double-ended handoff
        handoff.begin()

        assertTrue(handoff.inFlight, "a stray end() must not swallow a later real handoff")
    }
}
