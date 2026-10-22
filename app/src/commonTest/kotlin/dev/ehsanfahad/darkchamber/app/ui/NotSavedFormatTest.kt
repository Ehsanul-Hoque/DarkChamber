package dev.ehsanfahad.darkchamber.app.ui

import dev.ehsanfahad.darkchamber.app.root.NotSavedRecord
import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the notice says. Written from the failure path: each line names the cause its reason stands
 * for and nothing else, because the code knows that much and no more.
 */
class NotSavedFormatTest {

    private fun lines(vararg reports: Triple<NotSavedKind, NotSavedReason, Int>): List<String> {
        val record = NotSavedRecord()
        for ((kind, reason, count) in reports) record.report(kind, reason, count)
        return notSavedLines(record.summary.value)
    }

    @Test
    fun nothing_to_report_says_nothing() {
        assertEquals(emptyList(), lines())
    }

    @Test
    fun a_lock_names_the_lock() {
        assertEquals(
            listOf("1 video wasn't saved because the vault locked."),
            lines(Triple(NotSavedKind.Video, NotSavedReason.Locked, 1)),
        )
        assertEquals(
            listOf("2 photos and 1 video weren't saved because the vault locked."),
            lines(
                Triple(NotSavedKind.Photo, NotSavedReason.Locked, 2),
                Triple(NotSavedKind.Video, NotSavedReason.Locked, 1),
            ),
        )
    }

    @Test
    fun each_reason_reads_in_the_singular_and_the_plural() {
        val expected = mapOf(
            NotSavedReason.Locked to ("1 photo wasn't saved because the vault locked." to
                "3 photos weren't saved because the vault locked."),
            NotSavedReason.OutOfSpace to ("1 photo wasn't saved — your phone ran out of space." to
                "3 photos weren't saved — your phone ran out of space."),
            NotSavedReason.TooShort to ("1 photo was too short to save." to
                "3 photos were too short to save."),
            NotSavedReason.CouldNotSave to ("1 photo couldn't be saved." to
                "3 photos couldn't be saved."),
            NotSavedReason.SavedUnfiled to ("1 photo was saved to Unfiled — its album was deleted." to
                "3 photos were saved to Unfiled — their album was deleted."),
        )
        assertEquals(NotSavedReason.entries.toSet(), expected.keys, "every reason has its line")
        for ((reason, forms) in expected) {
            assertEquals(listOf(forms.first), lines(Triple(NotSavedKind.Photo, reason, 1)), "$reason, one")
            assertEquals(listOf(forms.second), lines(Triple(NotSavedKind.Photo, reason, 3)), "$reason, several")
        }
    }

    /** One photo and one video are two things: the verb follows the total, not the last count. */
    @Test
    fun the_verb_agrees_with_everything_the_line_counts() {
        assertEquals(
            "1 photo and 1 video couldn't be saved.",
            lines(
                Triple(NotSavedKind.Photo, NotSavedReason.CouldNotSave, 1),
                Triple(NotSavedKind.Video, NotSavedReason.CouldNotSave, 1),
            ).single(),
        )
        assertEquals(
            "1 photo and 1 video were too short to save.",
            lines(
                Triple(NotSavedKind.Photo, NotSavedReason.TooShort, 1),
                Triple(NotSavedKind.Video, NotSavedReason.TooShort, 1),
            ).single(),
        )
    }

    @Test
    fun several_reasons_give_one_line_each_in_a_fixed_order() {
        assertEquals(
            listOf(
                "1 video wasn't saved because the vault locked.",
                "1 video wasn't saved — your phone ran out of space.",
                "1 photo couldn't be saved.",
            ),
            lines(
                Triple(NotSavedKind.Photo, NotSavedReason.CouldNotSave, 1),
                Triple(NotSavedKind.Video, NotSavedReason.OutOfSpace, 1),
                Triple(NotSavedKind.Video, NotSavedReason.Locked, 1),
            ),
        )
    }

    /**
     * **No line borrows another reason's cause.** A full disk must never be blamed on a lock, a
     * generic failure must name nothing, and a short clip is not a space problem.
     */
    @Test
    fun no_line_names_a_cause_other_than_its_own() {
        val causeWords = mapOf(
            NotSavedReason.Locked to "locked",
            NotSavedReason.OutOfSpace to "space",
            NotSavedReason.TooShort to "short",
            NotSavedReason.SavedUnfiled to "deleted",
        )
        for (reason in NotSavedReason.entries) {
            for (count in listOf(1, 2)) {
                val line = lines(Triple(NotSavedKind.Video, reason, count)).single()
                for ((other, word) in causeWords) {
                    if (other != reason) assertFalse(word in line, "$reason borrows '$word': $line")
                }
            }
        }
    }

    @Test
    fun no_line_promises_more_than_the_code_does() {
        for (reason in NotSavedReason.entries) {
            for (count in listOf(1, 2)) {
                val line = lines(Triple(NotSavedKind.Photo, reason, count), Triple(NotSavedKind.Video, reason, count))
                    .single().lowercase()
                for (claim in listOf("silent", "wiped", "erased", "deleted from your phone", "recover")) {
                    assertFalse(claim in line, "'$claim' in: $line")
                }
                assertTrue(line.endsWith("."), line)
            }
        }
    }
}
