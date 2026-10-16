package dev.ehsanfahad.darkchamber.app.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the notice above the vault's grid is allowed to say.
 *
 * Two things, and nothing else: what the next press does, and that leaving locks the vault. The
 * phrasing to avoid is anything that reads as a promise about the session surviving — this notice
 * appears at the exact moment a user is about to lose it, and "you won't lose anything" is the
 * natural, wrong thing to write there (hard rule 6).
 */
class LeaveNoticeTest {

    @Test
    fun it_says_what_the_next_press_does() {
        val text = leaveNoticeText()

        assertTrue(text.contains("again"), "the offer is a second press: $text")
        assertTrue(text.contains("leave"), "and what that press does: $text")
    }

    @Test
    fun it_says_that_leaving_locks_the_vault() {
        assertTrue(leaveNoticeText().contains("locks the vault"), leaveNoticeText())
    }

    /**
     * The dishonest phrasings, asserted as absences. Leaving tears the session down — the key is
     * zeroized and the scratch purged — so nothing here may suggest the vault is still open
     * afterwards, or that coming back lands where the user left off.
     */
    @Test
    fun it_promises_nothing_about_the_session_surviving() {
        val text = leaveNoticeText().lowercase()

        assertFalse(text.contains("unlocked"), "the vault does not stay unlocked: $text")
        assertFalse(text.contains("stay"), "nothing stays: $text")
        assertFalse(text.contains("safe"), "a word that claims more than the threat model: $text")
        assertFalse(text.contains("where you left"), "the session does not survive leaving: $text")
    }
}
