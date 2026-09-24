package dev.ehsanfahad.darkchamber.feature.vault.api

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * What an implementation of the seam gets by saying nothing about deletion.
 *
 * Deleting someone's original is the one thing on this interface that cannot be undone, so the
 * answer to "may I?" has to be no until a platform says otherwise. A picker written later that
 * simply does not mention deletion loses the offer — which is the ordinary case anyway — rather
 * than inheriting a claim it cannot honour (hard rule #6).
 */
class PickedFileTest {

    @Test
    fun a_picked_file_that_says_nothing_about_deletion_cannot_delete() {
        assertFalse(SilentPickedFile().deletable)
    }

    @Test
    fun and_asking_it_to_delete_reports_that_nothing_happened() {
        assertFalse(SilentPickedFile().deleteOriginal(), "a default delete must not claim a success")
    }

    /** The smallest legal implementation: everything the seam requires, and not one word more. */
    private class SilentPickedFile : PickedFile {
        override val name: String = "picked"
        override val mime: String = "application/octet-stream"
        override val size: Long? = null

        override fun openSource(): RawSource = Buffer()

        override fun close() = Unit
    }
}
