package dev.ehsanfahad.darkchamber.feature.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingEnd
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingFailure
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fake's own scripting, which the recording tests lean on: they are only as good as the fake's
 * promise to end a recording when, and only when, the real seam would.
 */
class FakeRecordingTest {
    private val file = Path("tmp/scratch-test.mp4")

    private fun recordingCamera() = FakeCameraController().apply {
        start()
        setMode(CaptureMode.Video)
    }

    @Test
    fun a_stop_finalizes_with_the_scripted_end() = runTest {
        val camera = recordingCamera()
        camera.endOnStop = RecordingEnd.FinalizedAtSizeLimit

        val recording = camera.record(file, sizeLimitBytes = 1_000, withAudio = true)
        recording.stop()

        assertEquals(RecordingEnd.FinalizedAtSizeLimit, recording.awaitEnd())
        assertEquals(1, camera.recordingStops)
    }

    /** The finalize timeout is tested by a platform that never answers; the fake must be able to be one. */
    @Test
    fun with_no_scripted_end_a_stop_waits_until_the_test_ends_it() = runTest {
        val camera = recordingCamera()
        camera.endOnStop = null

        val recording = camera.record(file, sizeLimitBytes = 1_000, withAudio = false)
        val end = async { recording.awaitEnd() }
        recording.stop()
        testScheduler.advanceUntilIdle()
        assertFalse(end.isCompleted, "nothing ends it yet")

        camera.lastRecording!!.finish(RecordingEnd.NoFrames)
        assertEquals(RecordingEnd.NoFrames, end.await())
    }

    @Test
    fun releasing_the_camera_abandons_a_running_recording() = runTest {
        val camera = recordingCamera()
        val recording = camera.record(file, sizeLimitBytes = 1_000, withAudio = false)

        camera.stop()

        assertEquals(RecordingEnd.Abandoned, recording.awaitEnd())
    }

    @Test
    fun a_recording_that_cannot_start_comes_back_already_failed() = runTest {
        val closed = FakeCameraController().apply { setMode(CaptureMode.Video) }
        assertEquals(
            RecordingEnd.Failed(RecordingFailure.Other),
            closed.record(file, 1_000, withAudio = false).awaitEnd(),
            "no camera open",
        )

        val photo = FakeCameraController().apply { start() }
        assertEquals(
            RecordingEnd.Failed(RecordingFailure.Other),
            photo.record(file, 1_000, withAudio = false).awaitEnd(),
            "not in video mode",
        )

        val busy = recordingCamera()
        busy.record(file, 1_000, withAudio = false)
        assertEquals(
            RecordingEnd.Failed(RecordingFailure.Other),
            busy.record(file, 1_000, withAudio = false).awaitEnd(),
            "one already running",
        )
    }

    @Test
    fun the_fake_remembers_what_each_recording_was_asked_for() {
        val camera = recordingCamera()

        camera.record(file, sizeLimitBytes = 4_096, withAudio = true)

        assertEquals(FakeCameraController.RecordRequest(file, 4_096, withAudio = true), camera.recordRequests.single())
    }

    /** As for the camera: a microphone grant made in Settings is seen only in start. */
    @Test
    fun start_reads_the_microphone_permission_afresh() {
        val camera = FakeCameraController(microphonePermission = MicrophonePermission.Denied)
        camera.platformMicrophonePermission = MicrophonePermission.Granted
        assertEquals(MicrophonePermission.Denied, camera.microphonePermission.value, "not before start")

        camera.start()

        assertEquals(MicrophonePermission.Granted, camera.microphonePermission.value)
    }

    @Test
    fun quality_and_torch_follow_what_the_open_lens_offers() {
        val camera = FakeCameraController(
            capabilities = CameraCapabilities(setOf(Lens.Front), hasFlash = false, 1f, 1f, setOf(VideoQuality.HD)),
        )

        camera.setQuality(VideoQuality.UHD)
        camera.setTorch(true)

        assertEquals(VideoQuality.HD, camera.quality, "a quality the lens does not list is ignored")
        assertFalse(camera.torch, "no light, no torch")

        camera.capabilities.value = CameraCapabilities(setOf(Lens.Back), true, 1f, 1f, VideoQuality.entries.toSet())
        camera.setQuality(VideoQuality.UHD)
        camera.setTorch(true)
        assertEquals(VideoQuality.UHD, camera.quality)
        assertTrue(camera.torch)
    }
}
