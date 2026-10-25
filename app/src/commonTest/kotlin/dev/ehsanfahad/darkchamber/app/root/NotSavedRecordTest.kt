package dev.ehsanfahad.darkchamber.app.root

import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What did not make it into the vault, kept where a lock cannot reach it so it can be told after
 * the next unlock. Counts per kind and reason — nothing else exists in it to keep.
 */
class NotSavedRecordTest {

    @Test
    fun starts_empty() {
        assertTrue(NotSavedRecord().summary.value.isEmpty())
    }

    @Test
    fun reports_add_up_per_kind_and_reason() {
        val record = NotSavedRecord()

        record.report(NotSavedKind.Photo, NotSavedReason.Locked, count = 2)
        record.report(NotSavedKind.Photo, NotSavedReason.Locked, count = 1)
        record.report(NotSavedKind.Video, NotSavedReason.Locked, count = 1)
        record.report(NotSavedKind.Video, NotSavedReason.OutOfSpace, count = 1)

        val summary = record.summary.value
        assertEquals(3, summary.count(NotSavedKind.Photo, NotSavedReason.Locked))
        assertEquals(1, summary.count(NotSavedKind.Video, NotSavedReason.Locked))
        assertEquals(1, summary.count(NotSavedKind.Video, NotSavedReason.OutOfSpace))
        assertEquals(0, summary.count(NotSavedKind.Photo, NotSavedReason.OutOfSpace), "reasons stay apart")
    }

    /** A report of nothing is no report: a lock that abandoned no shots must not raise a notice. */
    @Test
    fun a_report_of_none_leaves_it_empty() {
        val record = NotSavedRecord()

        record.report(NotSavedKind.Photo, NotSavedReason.Locked, count = 0)

        assertTrue(record.summary.value.isEmpty())
    }

    /** Shown once: once the user has seen it, the next unlock says nothing about it again. */
    @Test
    fun clearing_empties_it_and_later_reports_start_from_zero() {
        val record = NotSavedRecord()
        record.report(NotSavedKind.Video, NotSavedReason.Locked, count = 1)

        record.clear()
        assertTrue(record.summary.value.isEmpty())

        record.report(NotSavedKind.Video, NotSavedReason.Locked, count = 1)
        assertEquals(1, record.summary.value.count(NotSavedKind.Video, NotSavedReason.Locked))
    }
}
