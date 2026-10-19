package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.camera.FakeCameraController
import dev.ehsanfahad.darkchamber.feature.camera.FakeCaptureVault
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
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val queue = CaptureQueue(
            camera = camera,
            save = SavePhoto(vault, clock = { 0L }, nameFor = { "p.jpg" }),
            albumId = byteArrayOf(9),
            scope = CoroutineScope(dispatcher + sessionJob),
            ioContext = dispatcher,
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

    private fun captured(seed: Int): CaptureResult = CaptureResult.Captured(CapturedPhoto(jpegLike(seed), 0))
}
