package dev.ehsanfahad.darkchamber.app.system

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android has no clipboard expiry, so the clear is ours to schedule — and ours to get right about
 * *what* it clears. The clipboard is shared: a minute after copying a recovery key, what is on it
 * may well belong to someone else.
 */
class ShouldClearClipTest {
    @Test
    fun a_clip_that_is_still_ours_is_cleared() {
        assertTrue(shouldClearClip(ours = 1_000L, current = 1_000L))
    }

    @Test
    fun a_clip_someone_else_has_since_copied_is_left_alone() {
        // Deleting it would be destroying the user's own work to tidy up after ourselves.
        assertFalse(shouldClearClip(ours = 1_000L, current = 2_000L))
    }

    @Test
    fun an_unknowable_clipboard_is_cleared_anyway() {
        // No timestamp before API 26, and a read can be refused. Leaving a recovery key on the
        // clipboard for the rest of the session is the worse of the two mistakes.
        assertTrue(shouldClearClip(ours = null, current = null), "we never knew what we set")
        assertTrue(shouldClearClip(ours = null, current = 2_000L), "still no way to compare")
        assertTrue(shouldClearClip(ours = 1_000L, current = null), "cannot read it back")
    }
}
