package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.camera.FakeCameraController
import dev.ehsanfahad.darkchamber.feature.camera.FakeCaptureVault
import dev.ehsanfahad.darkchamber.feature.camera.FakeClipFiles
import dev.ehsanfahad.darkchamber.feature.camera.FakeFreeSpace
import dev.ehsanfahad.darkchamber.feature.camera.GateDispatcher
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingEnd
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingFailure
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * One recording's life, from start to the lane or the bin, on every way out (D4): stop, close, the
 * size limit, no frames, a full disk, a platform failure and a lock — while recording, while the
 * platform finalizes, and once the lane holds the clip.
 *
 * "File present" is the clip's `deleted` flag; "object and row" are an import that answered `Ok`.
 * The real-vault cells — thumbnails included — are in `CameraVaultTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoRecorderTest {

    private class Harness(test: TestScope, free: Long? = 64_000_000_000L, laneIo: CoroutineContext? = null) {
        val camera = FakeCameraController().apply {
            start()
            setMode(CaptureMode.Video)
        }
        val vault = FakeCaptureVault()
        val clips = FakeClipFiles()
        val freeSpace = FakeFreeSpace(free)
        val sessionJob = Job()
        val reports = mutableListOf<Triple<NotSavedKind, NotSavedReason, Int>>()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(dispatcher + sessionJob)
        val notSaved = { kind: NotSavedKind, reason: NotSavedReason, count: Int -> reports += Triple(kind, reason, count) }
        val lane = VideoLane(vault, clock = { 0L }, scope = scope, ioContext = laneIo ?: dispatcher, notSaved = notSaved)
            .apply { screenOpen = true }
        var soundWanted = true
        val recorder = VideoRecorder(
            camera = camera,
            lane = lane,
            clips = clips,
            freeSpace = freeSpace,
            albumId = byteArrayOf(4),
            scope = scope,
            notSaved = notSaved,
            soundWanted = { soundWanted },
        )

        val clip get() = clips.made.single()
        val savedOk get() = vault.imported.size
    }

    // --- Starting ---

    @Test
    fun a_recording_starts_into_a_fresh_clip_with_the_limit_from_free_space_and_sound_when_allowed() = runTest {
        val h = Harness(this, free = RECORDING_RESERVE_BYTES + 4_000_000_000L)

        assertTrue(h.recorder.start())

        val asked = h.camera.recordRequests.single()
        assertEquals(h.clip.path, asked.file, "the platform writes where it is told")
        assertEquals(2_000_000_000L, asked.sizeLimitBytes)
        assertTrue(asked.withAudio)
        val active = assertNotNull(h.recorder.state.value.active)
        assertEquals(2_000_000_000L, active.limitBytes, "the status line counts down from this")
        assertTrue(active.withAudio)
        h.sessionJob.cancel()
    }

    @Test
    fun with_the_microphone_refused_it_records_without_sound() = runTest {
        val h = Harness(this)
        h.camera.microphonePermission.value = MicrophonePermission.Denied

        h.recorder.start()

        assertFalse(h.camera.recordRequests.single().withAudio)
        assertFalse(h.recorder.state.value.active!!.withAudio)
        h.sessionJob.cancel()
    }

    @Test
    fun with_sound_turned_off_it_records_without_sound_though_the_microphone_is_allowed() = runTest {
        val h = Harness(this)
        h.soundWanted = false

        h.recorder.start()

        assertFalse(h.camera.recordRequests.single().withAudio)
        assertFalse(h.recorder.state.value.active!!.withAudio, "so the no-sound mark shows")
        h.sessionJob.cancel()
    }

    @Test
    fun too_little_space_refuses_to_start_and_says_why() = runTest {
        val h = Harness(this, free = RECORDING_RESERVE_BYTES)

        assertFalse(h.recorder.start())

        assertTrue(h.camera.recordRequests.isEmpty(), "the camera was never asked")
        assertTrue(h.clips.made.isEmpty(), "and no file was reserved")
        assertEquals(RecorderNotice.NotEnoughSpace, h.recorder.state.value.notice)
        h.sessionJob.cancel()
    }

    @Test
    fun unknown_free_space_refuses_without_claiming_the_disk_is_full() = runTest {
        val h = Harness(this, free = null)

        assertFalse(h.recorder.start())

        assertEquals(RecorderNotice.SpaceUnknown, h.recorder.state.value.notice)
        h.sessionJob.cancel()
    }

    @Test
    fun a_second_start_while_recording_is_refused() = runTest {
        val h = Harness(this)
        h.recorder.start()

        assertFalse(h.recorder.start())

        assertEquals(1, h.camera.recordRequests.size)
        h.sessionJob.cancel()
    }

    /** One clip at a time across the whole session: a clip still encrypting blocks the next. */
    @Test
    fun recording_is_refused_while_the_lane_holds_a_clip() = runTest {
        val h = Harness(this, laneIo = GateDispatcher())
        h.recorder.start()
        h.recorder.stop()
        testScheduler.advanceUntilIdle()
        assertTrue(h.lane.state.value.busy, "handed over, not yet encrypted")

        assertFalse(h.recorder.start())
        assertEquals(1, h.camera.recordRequests.size)
        h.sessionJob.cancel()
    }

    // --- Recording × each way out ---

    @Test
    fun stop_finalizes_and_hands_the_clip_to_the_lane() = runTest {
        val h = Harness(this)
        h.recorder.start()

        h.recorder.stop()
        testScheduler.advanceUntilIdle()

        assertEquals(1, h.camera.recordingStops)
        assertEquals(1, h.savedOk, "saved")
        assertContentEquals(byteArrayOf(4), h.vault.imported.single().albumId, "filed where the camera was opened")
        assertTrue(h.clip.deleted, "and its file gone")
        assertNull(h.recorder.state.value.active)
        assertTrue(h.camera.isOpen, "the camera stays live after a stop")
        h.sessionJob.cancel()
    }

    @Test
    fun a_short_clip_with_frames_is_kept() = runTest {
        val h = Harness(this)
        h.recorder.start()
        h.camera.lastRecording!!.report(RecordingProgress(10_000, 300.milliseconds))

        h.recorder.stop()
        testScheduler.advanceUntilIdle()

        assertEquals(1, h.savedOk, "no minimum length")
        h.sessionJob.cancel()
    }

    @Test
    fun a_clip_with_no_frames_is_deleted_and_called_too_short() = runTest {
        val h = Harness(this)
        h.camera.endOnStop = RecordingEnd.NoFrames
        h.recorder.start()

        h.recorder.stop()
        testScheduler.advanceUntilIdle()

        assertTrue(h.clip.deleted)
        assertTrue(h.vault.imported.isEmpty(), "nothing to import")
        assertEquals(RecorderNotice.TooShort, h.recorder.state.value.notice)
        assertTrue(h.reports.isEmpty())
        h.sessionJob.cancel()
    }

    @Test
    fun the_size_limit_keeps_the_clip_and_says_it_stopped_for_space() = runTest {
        val h = Harness(this)
        h.recorder.start()

        h.camera.lastRecording!!.finish(RecordingEnd.FinalizedAtSizeLimit)
        testScheduler.advanceUntilIdle()

        assertEquals(1, h.savedOk, "kept")
        assertEquals(RecorderNotice.StoppedAtLimit, h.recorder.state.value.notice)
        h.sessionJob.cancel()
    }

    /** Another app filled the disk first: stopping may have left room, so the encrypt is tried once. */
    @Test
    fun a_full_disk_before_the_limit_gets_one_encrypt_attempt_then_the_file_goes() = runTest {
        val h = Harness(this)
        h.vault.nextResult = { Outcome.Err(VaultError.Storage.Full) }
        h.recorder.start()

        h.camera.lastRecording!!.finish(RecordingEnd.Failed(RecordingFailure.StorageFull))
        testScheduler.advanceUntilIdle()

        assertEquals(1, h.vault.imported.size, "one attempt")
        assertTrue(h.clip.deleted, "deleted at once when it fails")
        assertEquals(VideoNotice.OutOfSpace, h.lane.state.value.notice)
        h.sessionJob.cancel()
    }

    @Test
    fun a_platform_failure_deletes_the_file_and_imports_nothing() = runTest {
        val h = Harness(this)
        h.recorder.start()

        h.camera.lastRecording!!.finish(RecordingEnd.Failed(RecordingFailure.Other))
        testScheduler.advanceUntilIdle()

        assertTrue(h.clip.deleted)
        assertTrue(h.vault.imported.isEmpty(), "a file the platform says is broken is not trusted")
        assertEquals(RecorderNotice.RecordingFailed, h.recorder.state.value.notice)
        h.sessionJob.cancel()
    }

    @Test
    fun a_recording_the_platform_refused_to_start_is_cleaned_up_as_a_failure() = runTest {
        val h = Harness(this)
        h.camera.setMode(CaptureMode.Photo) // the fake refuses outside video

        h.recorder.start()
        testScheduler.advanceUntilIdle()

        assertTrue(h.clip.deleted)
        assertEquals(RecorderNotice.RecordingFailed, h.recorder.state.value.notice)
        assertNull(h.recorder.state.value.active)
        h.sessionJob.cancel()
    }

    @Test
    fun a_lock_while_recording_deletes_the_file_and_records_a_video_lost_to_it() = runTest {
        val h = Harness(this)
        h.recorder.start()
        testScheduler.advanceUntilIdle()

        h.sessionJob.cancel()
        h.camera.stop()
        testScheduler.advanceUntilIdle()

        assertTrue(h.clip.deleted)
        assertTrue(h.vault.imported.isEmpty())
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.Locked, 1)), h.reports)
    }

    /** The teardown may release the camera before it cancels the scope; the clip is lost to the lock either way. */
    @Test
    fun a_recording_abandoned_by_the_teardown_is_recorded_once_as_lost_to_the_lock() = runTest {
        val h = Harness(this)
        h.recorder.start()
        testScheduler.advanceUntilIdle()

        h.camera.stop()
        testScheduler.advanceUntilIdle()
        h.sessionJob.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue(h.clip.deleted)
        assertTrue(h.vault.imported.isEmpty(), "an abandoned file is never saved")
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.Locked, 1)), h.reports)
    }

    // --- Close ---

    /**
     * Back or ✕ while recording keeps the clip: finalize, hand it to the lane, and only then release
     * the camera — releasing mid-capture would abort the file.
     */
    @Test
    fun close_while_recording_finalizes_then_hands_over_then_releases() = runTest {
        val encrypt = GateDispatcher()
        val h = Harness(this, laneIo = encrypt)
        h.recorder.start()
        val order = mutableListOf<String>()

        launch {
            h.recorder.close(release = {
                order += "release (stopped ${h.camera.recordingStops}, lane busy ${h.lane.state.value.busy})"
                h.camera.stop()
            })
        }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("release (stopped 1, lane busy true)"), order)
        encrypt.runAll()
        testScheduler.advanceUntilIdle()
        assertEquals(1, h.savedOk, "the clip finished saving after the screen went")
        assertTrue(h.reports.isEmpty())
        h.sessionJob.cancel()
    }

    @Test
    fun close_with_nothing_recording_releases_at_once() = runTest {
        val h = Harness(this)
        var released = false

        h.recorder.close(release = { released = true })

        assertTrue(released)
        h.sessionJob.cancel()
    }

    /**
     * The platform never answers the stop. After about three seconds the clip is abandoned so the
     * camera's in-use indicator always goes off: file deleted, camera released, recorded as not saved.
     */
    @Test
    fun a_finalize_that_never_comes_is_abandoned_after_the_timeout() = runTest {
        val h = Harness(this)
        h.camera.endOnStop = null
        h.recorder.start()
        var released = false

        launch { h.recorder.close(release = { released = true; h.camera.stop() }) }
        advanceTimeBy(FINALIZE_TIMEOUT - 1.milliseconds)
        testScheduler.runCurrent()
        assertFalse(released, "still waiting just before the timeout")

        testScheduler.advanceUntilIdle()
        assertTrue(released)
        assertTrue(h.clip.deleted)
        assertTrue(h.vault.imported.isEmpty())
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.CouldNotSave, 1)), h.reports)
        h.sessionJob.cancel()
    }

    @Test
    fun after_close_a_clip_with_no_frames_goes_to_the_record_as_too_short() = runTest {
        val h = Harness(this)
        h.camera.endOnStop = RecordingEnd.NoFrames
        h.recorder.start()

        launch { h.recorder.close(release = { h.camera.stop() }) }
        testScheduler.advanceUntilIdle()

        assertTrue(h.clip.deleted)
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.TooShort, 1)), h.reports)
        h.sessionJob.cancel()
    }

    @Test
    fun after_close_a_platform_failure_goes_to_the_record() = runTest {
        val h = Harness(this)
        h.camera.endOnStop = RecordingEnd.Failed(RecordingFailure.Other)
        h.recorder.start()

        launch { h.recorder.close(release = { h.camera.stop() }) }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.CouldNotSave, 1)), h.reports)
        h.sessionJob.cancel()
    }

    // --- Finalizing × each way out ---

    @Test
    fun a_lock_while_finalizing_deletes_the_file_and_records_it() = runTest {
        val h = Harness(this)
        h.camera.endOnStop = null
        h.recorder.start()
        h.recorder.stop()
        testScheduler.advanceUntilIdle()
        assertTrue(h.recorder.state.value.stopping, "waiting on the platform")

        h.sessionJob.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue(h.clip.deleted)
        assertTrue(h.vault.imported.isEmpty())
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.Locked, 1)), h.reports)
    }

    @Test
    fun a_full_disk_reported_while_finalizing_still_gets_its_one_attempt() = runTest {
        val h = Harness(this)
        h.camera.endOnStop = RecordingEnd.Failed(RecordingFailure.StorageFull)
        h.recorder.start()

        h.recorder.stop()
        testScheduler.advanceUntilIdle()

        assertEquals(1, h.savedOk)
        h.sessionJob.cancel()
    }

    // --- Encrypting × each way out (the lane's own tests cover the rest) ---

    @Test
    fun close_while_the_lane_encrypts_releases_at_once_and_a_failure_goes_to_the_record() = runTest {
        val encrypt = GateDispatcher()
        val h = Harness(this, laneIo = encrypt)
        h.vault.nextResult = { Outcome.Err(VaultError.Storage.Full) }
        h.recorder.start()
        h.recorder.stop()
        testScheduler.advanceUntilIdle()
        assertEquals(1, encrypt.pending, "the clip is mid-encrypt")
        var released = false

        h.recorder.close(release = { released = true })
        assertTrue(released, "nothing left to finalize, so nothing to wait for")
        h.lane.screenOpen = false // as the screen does when it goes
        encrypt.runAll()
        testScheduler.advanceUntilIdle()

        assertTrue(h.clip.deleted)
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.OutOfSpace, 1)), h.reports)
        h.sessionJob.cancel()
    }

    /** The album went while recording: stop, and the lane files the clip unfiled and says where. */
    @Test
    fun a_clip_whose_album_went_while_recording_is_saved_unfiled() = runTest {
        val h = Harness(this)
        h.vault.albumGoesMissing = true
        h.recorder.start()

        h.recorder.stop()
        testScheduler.advanceUntilIdle()

        assertEquals(1, h.savedOk)
        assertEquals(VideoNotice.SavedUnfiled, h.lane.state.value.notice)
        h.sessionJob.cancel()
    }
}
