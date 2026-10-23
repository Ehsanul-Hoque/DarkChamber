package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.camera.FakeCameraController
import dev.ehsanfahad.darkchamber.feature.camera.FakeCaptureVault
import dev.ehsanfahad.darkchamber.feature.camera.GateDispatcher
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureResult
import dev.ehsanfahad.darkchamber.feature.camera.api.CapturedPhoto
import dev.ehsanfahad.darkchamber.feature.camera.jpegLike
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The capture queue: each shutter press captures, the saves run one after another, and a bounded
 * number of photos may wait in memory at once.
 *
 * Every dispatcher shares the test's scheduler and every capture is held until the test releases
 * it, so nothing here passes on elapsed time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureQueueTest {

    private class Harness(test: TestScope) {
        val camera = FakeCameraController().apply { holdCaptures() }
        val vault = FakeCaptureVault()
        val sessionJob = Job()
        val reports = mutableListOf<Triple<NotSavedKind, NotSavedReason, Int>>()
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val queue = CaptureQueue(
            camera = camera,
            save = SavePhoto(vault, clock = { 0L }, nameFor = { "p.jpg" }),
            albumId = byteArrayOf(9),
            scope = CoroutineScope(dispatcher + sessionJob),
            ioContext = dispatcher,
            notSaved = { kind, reason, count -> reports += Triple(kind, reason, count) },
        )
    }

    @Test
    fun one_press_captures_and_saves_one_photo() = runTest {
        val h = Harness(this)

        h.queue.shoot()
        testScheduler.advanceUntilIdle()
        h.camera.release()
        testScheduler.advanceUntilIdle()

        assertEquals(1, h.vault.imported.size)
        assertContentEquals(byteArrayOf(9), h.vault.imported.single().albumId)
        assertEquals(0, h.queue.state.value.holding, "nothing is left waiting")
        assertEquals(1, h.queue.state.value.saved)
        h.sessionJob.cancel()
    }

    @Test
    fun the_shutter_refuses_once_the_bound_is_held_and_returns_when_one_completes() = runTest {
        val h = Harness(this)

        repeat(3) { assertTrue(h.queue.shoot(), "press ${it + 1} is accepted") }
        testScheduler.advanceUntilIdle()
        assertFalse(h.queue.state.value.shutterEnabled, "three held is the bound")
        assertFalse(h.queue.shoot(), "a fourth press is refused")
        assertEquals(3, h.camera.captures, "and never reaches the camera")

        h.camera.release()
        testScheduler.advanceUntilIdle()
        assertTrue(h.queue.state.value.shutterEnabled, "one saved frees a place")
        assertEquals(2, h.queue.state.value.holding)
        h.sessionJob.cancel()
    }

    @Test
    fun photos_are_saved_in_the_order_they_were_taken() = runTest {
        val h = Harness(this)
        repeat(3) { h.queue.shoot() }
        testScheduler.advanceUntilIdle()

        for (seed in 1..3) h.camera.release(captured(seed))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf<Byte>(1, 2, 3), h.vault.imported.map { it.bytes[2] })
        h.sessionJob.cancel()
    }

    @Test
    fun a_failed_capture_is_reported_and_does_not_stop_the_next() = runTest {
        val h = Harness(this)
        repeat(2) { h.queue.shoot() }
        testScheduler.advanceUntilIdle()

        h.camera.release(CaptureResult.Failed)
        testScheduler.advanceUntilIdle()
        assertEquals(CaptureProblem.CaptureFailed, h.queue.state.value.problem)
        assertEquals(1, h.queue.state.value.holding, "the failed one no longer counts as held")

        h.camera.release(captured(2))
        testScheduler.advanceUntilIdle()
        assertEquals(1, h.vault.imported.size, "the next photo still saves")
        assertNull(h.queue.state.value.problem, "and a saved photo clears the problem")
        h.sessionJob.cancel()
    }

    @Test
    fun a_failed_save_is_reported_and_the_photos_behind_it_still_save() = runTest {
        val h = Harness(this)
        var calls = 0
        h.vault.nextResult = { if (++calls == 1) Outcome.Err(VaultError.Io(IllegalStateException("disk"))) else Outcome.Ok(byteArrayOf(calls.toByte())) }
        repeat(2) { h.queue.shoot() }
        testScheduler.advanceUntilIdle()
        h.camera.release(captured(1))
        testScheduler.advanceUntilIdle()

        assertEquals(CaptureProblem.SaveFailed, h.queue.state.value.problem)
        assertEquals(0, h.queue.state.value.saved, "a failed save is not counted as saved")

        h.camera.release(captured(2))
        testScheduler.advanceUntilIdle()
        assertEquals(1, h.queue.state.value.saved)
        assertContentEquals(byteArrayOf(2), h.queue.state.value.lastSavedId)
        h.sessionJob.cancel()
    }

    /**
     * The lock lands **inside** the first save, with two more photos already captured and waiting.
     * The save in progress sees the session go (its import would stop at the next read), and the
     * two waiting photos are cleared without ever reaching the vault.
     */
    @Test
    fun a_lock_mid_queue_stops_the_save_and_clears_every_waiting_photo() = runTest {
        val h = Harness(this)
        val photos = (1..3).map { captured(it) }
        h.vault.duringImport = { h.sessionJob.cancel() }
        repeat(3) { h.queue.shoot() }
        testScheduler.advanceUntilIdle()
        photos.forEach { h.camera.release(it) }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(false), h.vault.activeAfterImports, "the save in progress saw the lock")
        assertEquals(1, h.vault.imported.size, "nothing behind it reached the vault")
        for ((n, photo) in photos.withIndex()) {
            val jpeg = (photo as CaptureResult.Captured).photo.jpeg
            assertTrue(jpeg.all { it == 0.toByte() }, "photo ${n + 1}'s bytes are cleared")
        }
        assertFalse(h.queue.shoot(), "and a locked queue takes no more")
    }

    /**
     * The lock lands after a photo has left the queue for the encrypt but before the encrypt has
     * started on its own thread — so the save never runs, and its clearing with it. The photo is no
     * longer waiting, so draining the queue cannot reach it either; it must still be cleared.
     */
    @Test
    fun a_lock_before_the_encrypt_starts_still_clears_the_photo() = runTest {
        val camera = FakeCameraController().apply { holdCaptures() }
        val vault = FakeCaptureVault()
        val sessionJob = Job()
        val worker = GateDispatcher()
        val queue = CaptureQueue(
            camera = camera,
            save = SavePhoto(vault, clock = { 0L }, nameFor = { "p.jpg" }),
            albumId = null,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + sessionJob),
            ioContext = worker,
            notSaved = { _, _, _ -> },
        )
        val photo = captured(1)

        queue.shoot()
        testScheduler.advanceUntilIdle()
        camera.release(photo)
        testScheduler.advanceUntilIdle()
        assertEquals(1, worker.pending, "the photo is waiting on the encrypt")

        sessionJob.cancel()
        worker.runAll()
        testScheduler.advanceUntilIdle()

        assertEquals(0, vault.imported.size, "the encrypt never ran")
        val jpeg = (photo as CaptureResult.Captured).photo.jpeg
        assertTrue(jpeg.all { it == 0.toByte() }, "and the photo's bytes are cleared anyway")
    }

    /**
     * The lock lands after a photo has been handed to the waiting saver but before the saver has
     * resumed with it — so the photo is neither in the queue for the drain nor in a save that clears
     * it. It must still be cleared.
     */
    @Test
    fun a_lock_while_a_photo_is_being_handed_to_the_saver_still_clears_it() = runTest {
        val photo = captured(1)
        val camera = FakeCameraController().apply { nextCapture = { photo } }
        val vault = FakeCaptureVault()
        val sessionJob = Job()
        val main = GateDispatcher()
        val queue = CaptureQueue(
            camera = camera,
            save = SavePhoto(vault, clock = { 0L }, nameFor = { "p.jpg" }),
            albumId = null,
            scope = CoroutineScope(main + sessionJob),
            ioContext = main,
            notSaved = { _, _, _ -> },
        )
        main.runAll() // the saver is now waiting for a photo

        queue.shoot()
        main.runOne() // the capture returns and hands its photo over; the saver's resumption is queued
        assertEquals(1, main.pending, "the saver has been handed the photo but not yet run")

        sessionJob.cancel()
        main.runAll()

        assertEquals(0, vault.imported.size, "the save never ran")
        val jpeg = (photo as CaptureResult.Captured).photo.jpeg
        assertTrue(jpeg.all { it == 0.toByte() }, "and the photo's bytes are cleared anyway")
    }

    /**
     * **What a lock abandoned is told after the next unlock.** Every photo held — waiting, in the
     * encrypt, or still in the platform's hands — counts, because each was a press the user made and
     * none of them reached the vault.
     */
    @Test
    fun a_lock_reports_every_photo_it_abandoned() = runTest {
        val h = Harness(this)
        val photos = (1..2).map { captured(it) }
        h.vault.duringImport = { h.sessionJob.cancel() }
        repeat(3) { h.queue.shoot() }
        testScheduler.advanceUntilIdle()
        photos.forEach { h.camera.release(it) } // the third stays in the platform's hands
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(Triple(NotSavedKind.Photo, NotSavedReason.Locked, 3)), h.reports)
    }

    @Test
    fun a_lock_with_nothing_held_reports_nothing() = runTest {
        val h = Harness(this)
        h.queue.shoot()
        testScheduler.advanceUntilIdle()
        h.camera.release(captured(1))
        testScheduler.advanceUntilIdle()

        h.sessionJob.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), h.reports, "the one photo was saved; nothing was lost")
    }

    /**
     * While the camera is open its own screen says a save failed, so nothing is reported for later —
     * and it names a full disk only when the vault said that is what happened.
     */
    @Test
    fun a_failed_save_while_the_camera_is_open_is_left_to_its_screen() = runTest {
        for ((error, problem) in listOf(
            VaultError.Storage.Full to CaptureProblem.OutOfSpace,
            VaultError.Io(IllegalStateException("disk")) to CaptureProblem.SaveFailed,
        )) {
            val h = Harness(this)
            h.vault.nextResult = { Outcome.Err(error) }
            h.queue.shoot()
            testScheduler.advanceUntilIdle()
            h.camera.release(captured(1))
            testScheduler.advanceUntilIdle()

            assertEquals(problem, h.queue.state.value.problem, "for $error")
            assertEquals(emptyList(), h.reports)
            h.sessionJob.cancel()
        }
    }

    /**
     * Closed, nothing is left to say it — so a photo that fails to save after the camera has gone is
     * reported, naming a full disk only when the vault said that is what happened.
     */
    @Test
    fun a_failed_save_after_the_camera_closed_is_reported_with_what_is_known() = runTest {
        for ((error, reason) in listOf(
            VaultError.Storage.Full to NotSavedReason.OutOfSpace,
            VaultError.Io(IllegalStateException()) to NotSavedReason.CouldNotSave,
            VaultError.Storage.DestinationMissing to NotSavedReason.CouldNotSave,
        )) {
            val h = Harness(this)
            h.vault.nextResult = { Outcome.Err(error) }
            h.queue.shoot()
            testScheduler.advanceUntilIdle()
            h.queue.finish()
            h.camera.release(captured(1))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(Triple(NotSavedKind.Photo, reason, 1)), h.reports, "$error")
            h.sessionJob.cancel()
        }
    }

    /** A capture still in the platform's hands when the vault locks is never received. */
    @Test
    fun a_capture_that_returns_after_a_lock_is_never_saved() = runTest {
        val h = Harness(this)
        h.queue.shoot()
        testScheduler.advanceUntilIdle()

        h.sessionJob.cancel()
        testScheduler.advanceUntilIdle()
        h.camera.release(captured(1))
        testScheduler.advanceUntilIdle()

        assertEquals(0, h.vault.imported.size)
    }

    /**
     * Closing the camera finishes the queue rather than cancelling it: the photo still in the
     * platform's hands is saved, a press after it is refused, and only then does the queue stop
     * running in the session's scope.
     */
    @Test
    fun a_finished_queue_saves_what_it_holds_then_stops() = runTest {
        val h = Harness(this)
        h.queue.shoot()
        testScheduler.advanceUntilIdle()

        h.queue.finish()
        assertFalse(h.queue.shoot(), "a finished queue takes no more")
        testScheduler.advanceUntilIdle()
        assertTrue(h.sessionJob.children.any { it.isActive }, "it waits for the photo it holds")

        h.camera.release(captured(1))
        testScheduler.advanceUntilIdle()
        assertEquals(1, h.vault.imported.size, "that photo is saved")
        assertTrue(h.sessionJob.children.none { it.isActive }, "and nothing is left running in the session")
        h.sessionJob.cancel()
    }

    /** With nothing held, finishing stops the queue at once — a closed camera leaves nothing behind. */
    @Test
    fun an_idle_queue_stops_as_soon_as_it_is_finished() = runTest {
        val h = Harness(this)
        testScheduler.advanceUntilIdle()
        assertTrue(h.sessionJob.children.any { it.isActive }, "an open camera's queue is waiting for photos")

        h.queue.finish()
        testScheduler.advanceUntilIdle()

        assertTrue(h.sessionJob.children.none { it.isActive })
        h.sessionJob.cancel()
    }

    private fun captured(seed: Int): CaptureResult = CaptureResult.Captured(CapturedPhoto(jpegLike(seed), 0))

    /** Runs what it is handed only when the test says so — the encrypt, held at its doorstep. */
}
