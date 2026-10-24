package dev.ehsanfahad.darkchamber.feature.camera.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.stop
import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import dev.ehsanfahad.darkchamber.feature.camera.FakeCameraController
import dev.ehsanfahad.darkchamber.feature.camera.FakeCaptureVault
import dev.ehsanfahad.darkchamber.feature.camera.FakeClipFiles
import dev.ehsanfahad.darkchamber.feature.camera.FakeFreeSpace
import dev.ehsanfahad.darkchamber.feature.camera.GateDispatcher
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraSession
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingEnd
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingProgress
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import dev.ehsanfahad.darkchamber.feature.camera.cameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.domain.RECORDING_RESERVE_BYTES
import dev.ehsanfahad.darkchamber.feature.camera.domain.RecorderNotice
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The camera component driving video: settings pushed on every open, the microphone asked on the
 * first switch to video, record and stop, the session's lane and quality shared between screens,
 * and closing while recording.
 *
 * Every dispatcher is the test's or a gate the test opens, so nothing passes on elapsed time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraComponentVideoTest {

    private class Session(test: TestScope, ioContext: CoroutineContext? = null) {
        val vault = FakeCaptureVault()
        val clips = FakeClipFiles()
        val freeSpace = FakeFreeSpace()
        val sessionJob = Job()
        val reports = mutableListOf<Triple<NotSavedKind, NotSavedReason, Int>>()
        val dispatcher = UnconfinedTestDispatcher(test.testScheduler)
        val session = CameraSession(
            vault = vault,
            clips = clips,
            freeSpace = freeSpace,
            saveScope = CoroutineScope(dispatcher + sessionJob),
            notSaved = { kind, reason, count -> reports += Triple(kind, reason, count) },
            ioContext = ioContext ?: dispatcher,
            clock = { 0L },
        )
    }

    private class Screen(
        val s: Session,
        val camera: FakeCameraController = FakeCameraController(),
        destination: CameraDestination = CameraDestination.AllItems,
    ) {
        val lifecycle = LifecycleRegistry()
        var closes = 0
        val component: CameraComponent = cameraComponent(
            componentContext = DefaultComponentContext(lifecycle),
            session = s.session,
            camera = camera,
            destination = destination,
            unreadableNameLabel = "Name can't be read",
            onClose = { closes++ },
            mainContext = s.dispatcher,
        )
        val state: CameraState get() = component.state.value
    }

    private fun TestScope.videoScreen(s: Session = Session(this), camera: FakeCameraController = FakeCameraController()): Screen =
        Screen(s, camera).apply {
            lifecycle.resume()
            component.setMode(CaptureMode.Video)
        }

    // --- Mode, quality, torch ---

    @Test
    fun switching_to_video_tells_the_camera_and_the_screen() = runTest {
        val screen = videoScreen()

        assertEquals(CaptureMode.Video, screen.camera.mode)
        assertEquals(CaptureMode.Video, screen.state.mode)
        assertEquals(ShutterKind.Record, screen.state.shutter.kind)
        screen.s.sessionJob.cancel()
    }

    @Test
    fun the_quality_chip_switches_the_camera_and_the_session_remembers_it_for_the_next_screen() = runTest {
        val s = Session(this)
        val first = videoScreen(s)

        first.component.switchQuality()
        assertEquals(VideoQuality.UHD, first.camera.quality)
        assertEquals(VideoQuality.UHD, first.state.quality)
        first.lifecycle.destroy()

        val camera = FakeCameraController().apply { setQuality(VideoQuality.HD) }
        val second = Screen(s, camera).apply { lifecycle.resume() }
        assertEquals(VideoQuality.UHD, camera.quality, "pushed to the camera on the next open")
        assertEquals(VideoQuality.UHD, second.state.quality)
        s.sessionJob.cancel()
    }

    /** A lock ends the session, and the next session starts from the default. */
    @Test
    fun the_quality_is_not_remembered_across_a_lock() = runTest {
        val before = videoScreen()
        before.component.switchQuality()
        before.s.sessionJob.cancel()

        val after = Screen(Session(this)).apply { lifecycle.resume() }
        assertEquals(VideoQuality.HD, after.state.quality)
        after.s.sessionJob.cancel()
    }

    @Test
    fun the_quality_chip_does_nothing_where_it_is_not_shown() = runTest {
        val s = Session(this)
        val photo = Screen(s).apply { lifecycle.resume() }

        photo.component.switchQuality()

        assertEquals(VideoQuality.HD, photo.camera.quality, "photo mode shows no chip")
        s.sessionJob.cancel()
    }

    @Test
    fun the_torch_toggles_in_video_and_the_flash_control_leaves_it_alone() = runTest {
        val screen = videoScreen()

        screen.component.toggleTorch()
        assertTrue(screen.camera.torch)
        assertTrue(screen.state.torch)

        screen.component.cycleFlash()
        assertEquals(FlashMode.Off, screen.camera.flash, "no flash in video")
        screen.component.toggleTorch()
        assertFalse(screen.camera.torch)
        screen.s.sessionJob.cancel()
    }

    @Test
    fun leaving_video_puts_the_torch_out() = runTest {
        val screen = videoScreen()
        screen.component.toggleTorch()

        screen.component.setMode(CaptureMode.Photo)

        assertFalse(screen.camera.torch, "no torch left burning behind a photo viewfinder")
        assertFalse(screen.state.torch)
        screen.s.sessionJob.cancel()
    }

    // --- The microphone ---

    @Test
    fun the_microphone_is_asked_on_the_first_switch_to_video_never_on_entry_and_only_once() = runTest {
        val camera = FakeCameraController(microphonePermission = MicrophonePermission.NotAsked).apply {
            microphoneAnswer = MicrophonePermission.Denied
        }
        val screen = Screen(Session(this), camera).apply { lifecycle.resume() }
        assertEquals(0, camera.microphoneRequests, "entering the camera asks nothing about sound")

        screen.component.setMode(CaptureMode.Video)
        assertEquals(1, camera.microphoneRequests)
        assertEquals(MicrophonePermission.Denied, screen.state.microphone)

        screen.component.setMode(CaptureMode.Photo)
        camera.microphonePermission.value = MicrophonePermission.NotAsked
        screen.component.setMode(CaptureMode.Video)
        assertEquals(1, camera.microphoneRequests, "once per screen")
        screen.s.sessionJob.cancel()
    }

    @Test
    fun a_microphone_already_answered_is_not_asked_again() = runTest {
        val camera = FakeCameraController(microphonePermission = MicrophonePermission.Granted)
        videoScreen(camera = camera)

        assertEquals(0, camera.microphoneRequests)
    }

    @Test
    fun a_microphone_grant_made_in_settings_is_seen_on_the_next_start() = runTest {
        val camera = FakeCameraController(microphonePermission = MicrophonePermission.PermanentlyDenied)
        val screen = videoScreen(camera = camera)
        assertEquals(MicrophonePermission.PermanentlyDenied, screen.state.microphone)

        screen.lifecycle.stop()
        camera.platformMicrophonePermission = MicrophonePermission.Granted
        screen.lifecycle.resume()

        assertEquals(MicrophonePermission.Granted, screen.state.microphone)
        screen.s.sessionJob.cancel()
    }

    @Test
    fun the_microphone_hint_asks_while_the_system_will_and_opens_settings_once_it_will_not() = runTest {
        val denied = FakeCameraController(microphonePermission = MicrophonePermission.Denied).apply {
            microphoneAnswer = MicrophonePermission.Granted
        }
        val asking = videoScreen(camera = denied)
        val requestsBefore = denied.microphoneRequests
        asking.component.requestMicrophone()
        assertEquals(requestsBefore + 1, denied.microphoneRequests)
        assertEquals(0, denied.settingsOpened)

        val off = FakeCameraController(microphonePermission = MicrophonePermission.PermanentlyDenied)
        val settings = videoScreen(camera = off)
        settings.component.requestMicrophone()
        assertEquals(1, off.settingsOpened)
        assertEquals(0, off.microphoneRequests, "asking again would do nothing")
        asking.s.sessionJob.cancel()
        settings.s.sessionJob.cancel()
    }

    // --- The microphone toggle ---

    @Test
    fun turning_sound_off_records_without_it_and_the_session_remembers_until_the_lock() = runTest {
        val s = Session(this)
        val first = videoScreen(s)

        first.component.toggleSound()
        assertEquals(MicrophoneToggle(soundOn = false), first.state.microphoneToggle)
        first.component.shoot()
        assertFalse(first.camera.recordRequests.single().withAudio, "the microphone is allowed, but sound was turned off")
        assertTrue(first.state.showsNoSoundMark)
        first.component.shoot()
        first.lifecycle.destroy()

        val second = videoScreen(s)
        assertEquals(MicrophoneToggle(soundOn = false), second.state.microphoneToggle, "the next screen of the session keeps it")
        s.sessionJob.cancel()

        val afterLock = videoScreen(Session(this))
        assertEquals(MicrophoneToggle(soundOn = true), afterLock.state.microphoneToggle, "a lock forgets it")
        afterLock.s.sessionJob.cancel()
    }

    @Test
    fun turning_sound_back_on_records_with_it() = runTest {
        val screen = videoScreen()
        screen.component.toggleSound()
        screen.component.toggleSound()

        screen.component.shoot()

        assertTrue(screen.camera.recordRequests.single().withAudio)
        screen.s.sessionJob.cancel()
    }

    /**
     * While the microphone is refused the toggle cannot give sound, so a tap does what would: ask
     * again while the system still asks, Settings once it will not. It never just flips a switch that
     * changes nothing.
     */
    @Test
    fun a_tap_while_the_microphone_is_refused_asks_or_opens_settings_instead_of_flipping() = runTest {
        val denied = FakeCameraController(microphonePermission = MicrophonePermission.Denied).apply {
            microphoneAnswer = MicrophonePermission.Granted
        }
        val asking = videoScreen(camera = denied)
        val before = denied.microphoneRequests

        asking.component.toggleSound()

        assertEquals(before + 1, denied.microphoneRequests, "asked again")
        assertEquals(MicrophoneToggle(soundOn = true), asking.state.microphoneToggle, "and the grant gives sound")
        asking.s.sessionJob.cancel()

        val off = FakeCameraController(microphonePermission = MicrophonePermission.PermanentlyDenied)
        val settings = videoScreen(camera = off)
        settings.component.toggleSound()
        assertEquals(1, off.settingsOpened)
        assertTrue(settings.s.session.soundWanted.value, "still wanting sound, for when it is allowed")
        settings.s.sessionJob.cancel()
    }

    /** Turned off, then the microphone refused: a tap means "I want sound", so it asks for it. */
    @Test
    fun a_tap_on_a_toggle_turned_off_while_refused_wants_sound_and_asks_for_it() = runTest {
        val camera = FakeCameraController(microphonePermission = MicrophonePermission.Denied)
        val screen = videoScreen(camera = camera)
        screen.s.session.soundWanted.value = false
        val before = camera.microphoneRequests

        screen.component.toggleSound()

        assertTrue(screen.s.session.soundWanted.value)
        assertEquals(before + 1, camera.microphoneRequests)
        screen.s.sessionJob.cancel()
    }

    @Test
    fun the_toggle_does_nothing_while_recording() = runTest {
        val screen = videoScreen()
        screen.component.shoot()

        screen.component.toggleSound()

        assertTrue(screen.s.session.soundWanted.value, "sound is chosen before a recording starts")
        screen.s.sessionJob.cancel()
    }

    // --- Recording ---

    @Test
    fun the_shutter_records_and_stops_and_the_clip_is_saved() = runTest {
        val screen = videoScreen()

        screen.component.shoot()
        assertEquals(1, screen.camera.recordRequests.size)
        assertEquals(ShutterKind.Stop, screen.state.shutter.kind)
        assertEquals(CameraRow.Status, screen.state.row)
        assertTrue(screen.state.hasRecorded)

        screen.component.shoot()
        assertEquals(1, screen.camera.recordingStops)
        assertEquals(listOf("video/mp4"), screen.s.vault.imported.map { it.mime })
        assertEquals(ShutterKind.Record, screen.state.shutter.kind)
        assertEquals(1, screen.state.lane.saved)
        screen.s.sessionJob.cancel()
    }

    @Test
    fun the_recordings_progress_reaches_the_screen() = runTest {
        val screen = videoScreen()
        screen.component.shoot()

        screen.camera.lastRecording!!.report(RecordingProgress(5_000_000, 3.seconds))

        val active = assertNotNull(screen.state.recorder.active)
        assertEquals(RecordingProgress(5_000_000, 3.seconds), active.progress.value)
        screen.s.sessionJob.cancel()
    }

    @Test
    fun a_refused_start_reaches_the_screen_as_a_notice() = runTest {
        val s = Session(this)
        s.freeSpace.bytes = RECORDING_RESERVE_BYTES
        val screen = videoScreen(s)

        screen.component.shoot()

        assertTrue(screen.camera.recordRequests.isEmpty())
        assertEquals(RecorderNotice.NotEnoughSpace, screen.state.recorder.notice)
        s.sessionJob.cancel()
    }

    /**
     * One clip at a time across the session: with a clip still encrypting, record is out of reach —
     * and photos are still taken.
     */
    @Test
    fun record_is_refused_while_the_lane_is_busy_and_photos_are_still_taken() = runTest {
        val encrypt = GateDispatcher()
        val s = Session(this, ioContext = encrypt)
        val screen = videoScreen(s)
        screen.component.shoot()
        screen.component.shoot()
        assertTrue(screen.state.lane.busy, "the clip is encrypting")
        assertEquals(Shutter(ShutterKind.Record, enabled = false, waitingOnSave = true), screen.state.shutter)

        screen.component.shoot()
        assertEquals(1, screen.camera.recordRequests.size, "a press on a disabled record does nothing")

        screen.component.setMode(CaptureMode.Photo)
        screen.component.shoot()
        assertEquals(1, screen.camera.captures, "the photo was taken")
        s.sessionJob.cancel()
    }

    @Test
    fun a_camera_reopened_while_a_clip_encrypts_finds_record_out_of_reach() = runTest {
        val encrypt = GateDispatcher()
        val s = Session(this, ioContext = encrypt)
        val first = videoScreen(s)
        first.component.shoot()
        first.component.shoot()
        first.component.close()
        first.lifecycle.destroy()

        val second = videoScreen(s)
        assertFalse(second.state.shutter.enabled)
        assertTrue(second.state.shutter.waitingOnSave)

        encrypt.runAll()
        assertTrue(second.state.shutter.enabled, "free once the clip is saved")
        s.sessionJob.cancel()
    }

    @Test
    fun mode_and_lens_cannot_change_while_recording() = runTest {
        val screen = videoScreen()
        screen.component.shoot()

        screen.component.setMode(CaptureMode.Photo)
        screen.component.flipLens()

        assertEquals(CaptureMode.Video, screen.camera.mode, "switching mode would end the clip")
        assertEquals(Lens.Back, screen.camera.lens)
        screen.s.sessionJob.cancel()
    }

    @Test
    fun the_lanes_notices_reach_the_screen_and_a_closed_screen_sends_them_to_the_record() = runTest {
        val s = Session(this)
        s.vault.albumGoesMissing = true
        val screen = videoScreen(s)
        screen.component.shoot()
        screen.component.shoot()
        assertEquals(VideoNotice.SavedUnfiled, screen.state.lane.notice)
        assertTrue(s.reports.isEmpty())

        screen.component.close()
        screen.lifecycle.destroy()
        // A clip finishing after the screen has gone: the lane has no screen to tell.
        s.session.lane.save(s.clips.newClip(), albumId = null)
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.SavedUnfiled, 1)), s.reports)
        s.sessionJob.cancel()
    }

    /** The tile holds whatever was saved last, photo or clip, and says which. */
    @Test
    fun the_tile_follows_the_newest_saved_capture_of_either_kind() = runTest {
        val s = Session(this)
        var ids = 0
        s.vault.nextResult = { Outcome.Ok(byteArrayOf((++ids).toByte())) }
        val screen = Screen(s).apply { lifecycle.resume() }

        screen.component.shoot()
        assertEquals(LastShot(byteArrayOf(1), CaptureMode.Photo), screen.state.tile.lastShot)

        screen.component.setMode(CaptureMode.Video)
        screen.component.shoot()
        screen.component.shoot()
        assertEquals(LastShot(byteArrayOf(2), CaptureMode.Video), screen.state.tile.lastShot)

        screen.component.setMode(CaptureMode.Photo)
        screen.component.shoot()
        assertEquals(LastShot(byteArrayOf(3), CaptureMode.Photo), screen.state.tile.lastShot)
        s.sessionJob.cancel()
    }

    // --- Leaving ---

    /** ✕ while recording keeps the clip: finalize, hand over, then release the camera, then pop. */
    @Test
    fun close_while_recording_keeps_the_clip_and_releases_the_camera_only_after() = runTest {
        val screen = videoScreen()
        screen.camera.endOnStop = null
        screen.component.shoot()

        screen.component.close()
        assertEquals(1, screen.camera.recordingStops, "asked to finalize")
        assertTrue(screen.camera.isOpen, "the camera stays bound until the platform finalizes")
        assertEquals(0, screen.closes, "and the screen stays until then")

        screen.camera.lastRecording!!.finish(RecordingEnd.Finalized)
        assertFalse(screen.camera.isOpen, "released once the clip is handed over")
        assertEquals(1, screen.closes)
        assertEquals(listOf("video/mp4"), screen.s.vault.imported.map { it.mime })
        screen.s.sessionJob.cancel()
    }

    /**
     * The clip handed over by a close is saved after the screen has gone, so its failure goes to the
     * record — never to a notice on a screen nobody will see again.
     */
    @Test
    fun a_clip_that_fails_after_a_close_while_recording_goes_to_the_record() = runTest {
        val s = Session(this)
        s.vault.nextResult = { Outcome.Err(VaultError.Storage.Full) }
        val screen = videoScreen(s)
        screen.camera.endOnStop = null
        screen.component.shoot()

        screen.component.close()
        screen.camera.lastRecording!!.finish(RecordingEnd.Finalized)

        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.OutOfSpace, 1)), s.reports)
        s.sessionJob.cancel()
    }

    @Test
    fun a_second_close_while_finalizing_does_nothing_more() = runTest {
        val screen = videoScreen()
        screen.camera.endOnStop = null
        screen.component.shoot()

        screen.component.close()
        screen.component.close()
        screen.camera.lastRecording!!.finish(RecordingEnd.Finalized)

        assertEquals(1, screen.closes)
        screen.s.sessionJob.cancel()
    }

    /** The album went while recording: stop, and the clip is saved unfiled rather than lost. */
    @Test
    fun an_album_deleted_while_recording_stops_the_recording() = runTest {
        val s = Session(this)
        val trip = byteArrayOf(7)
        s.vault.albums.value = listOf(AlbumSummary(id = trip, name = Outcome.Ok("Trip"), itemCount = 0, coverItemId = null, createdAt = 1L))
        val screen = Screen(s, destination = CameraDestination.Album(trip)).apply {
            lifecycle.resume()
            component.setMode(CaptureMode.Video)
        }
        screen.component.shoot()
        assertNotNull(screen.state.recorder.active)

        s.vault.albumGoesMissing = true
        s.vault.albums.value = emptyList()

        assertNull(screen.state.recorder.active, "the recording stopped")
        assertEquals(1, screen.camera.recordingStops)
        assertEquals(VideoNotice.SavedUnfiled, screen.state.lane.notice, "and the clip went to Unfiled")
        s.sessionJob.cancel()
    }
}
