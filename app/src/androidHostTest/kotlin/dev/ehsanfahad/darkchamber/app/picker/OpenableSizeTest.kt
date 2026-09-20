package dev.ehsanfahad.darkchamber.app.picker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a content provider reports for `OpenableColumns.SIZE`, turned into a number we are willing to
 * show a user.
 *
 * Providers are allowed to not know. Cloud providers in particular return a negative value for a
 * file that has not been downloaded, and the column can be missing altogether — both mean "no
 * size", and a bar drawn against -1 or a guessed total would be a UI that lies about progress
 * (hard rule #6). Zero is a real answer: an empty file is a file.
 */
class OpenableSizeTest {
    @Test
    fun a_reported_size_is_taken_as_given() {
        assertEquals(1_048_576L, openableSizeOrNull(1_048_576L))
    }

    @Test
    fun an_empty_file_is_a_size_not_an_absence() {
        assertEquals(0L, openableSizeOrNull(0L))
    }

    @Test
    fun a_negative_size_means_the_provider_does_not_know() {
        assertNull(openableSizeOrNull(-1L))
    }

    @Test
    fun an_absent_column_means_the_provider_does_not_know() {
        assertNull(openableSizeOrNull(null))
    }
}
