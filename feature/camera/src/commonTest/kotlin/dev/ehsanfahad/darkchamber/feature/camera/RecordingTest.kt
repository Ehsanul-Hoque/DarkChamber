package dev.ehsanfahad.darkchamber.feature.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingEnd
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingFailure
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingProgress
import dev.ehsanfahad.darkchamber.feature.camera.api.ReportedRecording
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RecordingTest {

    /**
     * A platform can report more than one ending — CameraX finalizes after an unbind that our
     * teardown already called an abandon — and the lane must act on exactly one, the first. A
     * later "finalized" turning an abandoned clip into a kept one would save what a lock discarded.
     */
    @Test
    fun the_first_end_reported_is_the_one_a_recording_keeps() = runTest {
        val recording = ReportedRecording(onStop = {})

        assertTrue(recording.finish(RecordingEnd.Abandoned))
        assertFalse(recording.finish(RecordingEnd.Finalized), "a second end is refused")

        assertEquals(RecordingEnd.Abandoned, recording.awaitEnd())
    }

    @Test
    fun awaiting_the_end_suspends_until_the_platform_reports_one() = runTest {
        val recording = ReportedRecording(onStop = {})
        val end = async { recording.awaitEnd() }
        testScheduler.advanceUntilIdle()
        assertFalse(end.isCompleted)

        recording.finish(RecordingEnd.Failed(RecordingFailure.StorageFull))

        assertEquals(RecordingEnd.Failed(RecordingFailure.StorageFull), end.await())
    }

    /** The status line counts against a limit fixed at start; a late sample would move it after the end. */
    @Test
    fun progress_after_the_end_is_ignored() {
        val recording = ReportedRecording(onStop = {})
        val during = RecordingProgress(bytesRecorded = 1_000, duration = 2.seconds)

        recording.report(during)
        recording.finish(RecordingEnd.Finalized)
        recording.report(RecordingProgress(bytesRecorded = 9_000, duration = 9.seconds))

        assertEquals(during, recording.progress.value)
    }

    /**
     * Stop and back both ask the platform to finalize; pressing both, or pressing stop on a clip the
     * platform already ended, must not hand the platform a second stop for a recording it has closed.
     */
    @Test
    fun stop_asks_the_platform_once_and_never_after_the_end() {
        var asked = 0
        val recording = ReportedRecording(onStop = { asked++ })

        recording.stop()
        recording.stop()
        assertEquals(1, asked, "a second stop is not passed on")

        val ended = ReportedRecording(onStop = { asked++ })
        ended.finish(RecordingEnd.NoFrames)
        ended.stop()
        assertEquals(1, asked, "an ended recording has nothing to stop")
    }
}
