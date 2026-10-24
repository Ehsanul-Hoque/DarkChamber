package dev.ehsanfahad.darkchamber.feature.camera.presentation

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingProgress
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import dev.ehsanfahad.darkchamber.feature.camera.domain.ActiveRecording
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureProblem
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureQueueState
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationInfo
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationTitle
import dev.ehsanfahad.darkchamber.feature.camera.domain.MicrophoneHint
import dev.ehsanfahad.darkchamber.feature.camera.domain.RecorderNotice
import dev.ehsanfahad.darkchamber.feature.camera.domain.RecorderState
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoLaneState
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The camera screen's video decisions, each a function of state. */
class CameraVideoStateTest {

    private val bothQualities = setOf(VideoQuality.HD, VideoQuality.UHD)

    private fun state(
        mode: CaptureMode = CaptureMode.Video,
        recording: Boolean = false,
        withAudio: Boolean = true,
        stopping: Boolean = false,
        laneBusy: Boolean = false,
        lenses: Set<Lens> = setOf(Lens.Back, Lens.Front),
        hasFlash: Boolean = true,
        qualities: Set<VideoQuality> = bothQualities,
        quality: VideoQuality = VideoQuality.HD,
        torch: Boolean = false,
        flash: FlashMode = FlashMode.Auto,
        microphone: MicrophonePermission = MicrophonePermission.Granted,
        hasRecorded: Boolean = false,
        soundWanted: Boolean = true,
        permission: CameraPermission = CameraPermission.Granted,
        availability: CameraAvailability = CameraAvailability.Available,
        destination: DestinationInfo? = DestinationInfo(DestinationTitle.AllItems, 3L),
    ) = CameraState(
        destination = destination,
        permission = permission,
        availability = availability,
        capabilities = CameraCapabilities(lenses, hasFlash, 1f, 8f, qualities),
        queue = CaptureQueueState(holding = 0, bound = 3),
        flash = flash,
        mode = mode,
        quality = quality,
        torch = torch,
        recorder = RecorderState(
            active = if (recording) ActiveRecording(1_000, withAudio, MutableStateFlow(RecordingProgress.Zero)) else null,
            stopping = stopping,
        ),
        lane = VideoLaneState(busy = laneBusy),
        microphone = microphone,
        hasRecorded = hasRecorded,
        soundWanted = soundWanted,
    )

    // --- The row under the viewfinder ---

    @Test
    fun the_mode_labels_give_way_to_the_status_while_recording() {
        assertEquals(CameraRow.ModeLabels, state(recording = false).row)
        assertEquals(CameraRow.Status, state(recording = true).row)
        assertEquals(CameraRow.ModeLabels, state(mode = CaptureMode.Photo).row)
    }

    // --- Which controls show ---

    @Test
    fun flip_is_offered_with_two_lenses_and_never_while_recording() {
        assertTrue(state().flipOffered)
        assertFalse(state(recording = true).flipOffered, "switching lens mid-clip would end it")
        assertFalse(state(lenses = setOf(Lens.Back)).flipOffered)
    }

    /** In video the flash becomes the torch, and it stays while recording — light is wanted mid-clip. */
    @Test
    fun the_light_control_is_the_flash_in_photo_and_the_torch_in_video() {
        assertEquals(LightControl.Flash(FlashMode.Auto), state(mode = CaptureMode.Photo).light)
        assertEquals(LightControl.Torch(on = false), state().light)
        assertEquals(LightControl.Torch(on = true), state(torch = true, recording = true).light, "available while recording")
        assertNull(state(hasFlash = false).light, "no light on this lens, no control")
        assertNull(state(mode = CaptureMode.Photo, hasFlash = false).light)
        assertFalse(state(hasFlash = false).flashOffered)
    }

    /** A chip that offers nothing to switch to is hidden rather than disabled. */
    @Test
    fun the_quality_chip_shows_in_video_on_a_lens_with_a_choice_and_not_while_recording() {
        assertEquals(QualityChip(current = VideoQuality.HD, next = VideoQuality.UHD), state().qualityChip)
        assertEquals(QualityChip(current = VideoQuality.UHD, next = VideoQuality.HD), state(quality = VideoQuality.UHD).qualityChip)
        assertNull(state(qualities = setOf(VideoQuality.HD)).qualityChip, "a lens with only HD")
        // 4K chosen on the back lens, then flipped to a front lens that records only HD: the chip
        // would read "4K" over a recording made in HD.
        assertNull(
            state(quality = VideoQuality.UHD, qualities = setOf(VideoQuality.HD)).qualityChip,
            "the choice the lens cannot record is not named",
        )
        assertNull(state(recording = true).qualityChip, "hidden while recording")
        assertNull(state(mode = CaptureMode.Photo).qualityChip, "video only")
    }

    // --- The shutter ---

    @Test
    fun the_shutter_takes_a_photo_in_photo_mode_with_the_photo_rules() {
        assertEquals(Shutter(ShutterKind.Photo, enabled = true), state(mode = CaptureMode.Photo).shutter)
        assertEquals(
            Shutter(ShutterKind.Photo, enabled = false),
            state(mode = CaptureMode.Photo, permission = CameraPermission.Denied).shutter,
        )
    }

    @Test
    fun in_video_the_shutter_records_and_while_recording_it_stops() {
        assertEquals(Shutter(ShutterKind.Record, enabled = true), state().shutter)
        assertEquals(Shutter(ShutterKind.Stop, enabled = true), state(recording = true).shutter)
        assertEquals(Shutter(ShutterKind.Stop, enabled = false), state(recording = true, stopping = true).shutter, "pressed once already")
    }

    /** The lane holds one clip at a time; the control says why it is out of reach rather than just greying. */
    @Test
    fun record_is_unavailable_while_the_last_clip_is_still_saving_and_says_so() {
        assertEquals(Shutter(ShutterKind.Record, enabled = false, waitingOnSave = true), state(laneBusy = true).shutter)
    }

    @Test
    fun record_is_unavailable_where_a_photo_would_be_and_where_the_lens_records_no_video() {
        assertFalse(state(permission = CameraPermission.Denied).shutter.enabled)
        assertFalse(state(availability = CameraAvailability.InUseByAnotherApp).shutter.enabled)
        assertFalse(state(destination = DestinationInfo(DestinationTitle.AlbumGone, null)).shutter.enabled)
        assertFalse(state(qualities = emptySet()).shutter.enabled, "this lens records no video")
        assertFalse(state(qualities = emptySet()).shutter.waitingOnSave, "and that is not a save in progress")
    }

    // --- The viewfinder's shape ---

    @Test
    fun the_viewfinder_takes_the_captures_shape() {
        assertEquals(CaptureRatio.FourByThree, state(mode = CaptureMode.Photo).captureRatio)
        assertEquals(CaptureRatio.SixteenByNine, state().captureRatio)
        assertEquals(4f / 3f, CaptureRatio.FourByThree.longOverShort)
        assertEquals(16f / 9f, CaptureRatio.SixteenByNine.longOverShort)
    }

    // --- Sound ---

    @Test
    fun the_no_sound_mark_shows_only_on_a_recording_without_sound() {
        assertTrue(state(recording = true, withAudio = false).showsNoSoundMark)
        assertFalse(state(recording = true, withAudio = true).showsNoSoundMark)
        assertFalse(state(recording = false, microphone = MicrophonePermission.Denied).showsNoSoundMark, "only while recording")
    }

    @Test
    fun the_microphone_hint_shows_in_video_before_the_first_recording_with_what_would_help() {
        assertEquals(MicrophoneHint.Ask, state(microphone = MicrophonePermission.Denied).microphoneHint)
        assertEquals(MicrophoneHint.Settings, state(microphone = MicrophonePermission.PermanentlyDenied).microphoneHint)
        assertNull(state(microphone = MicrophonePermission.Granted).microphoneHint, "nothing to fix")
        assertNull(state(microphone = MicrophonePermission.Denied, hasRecorded = true).microphoneHint, "said once, before the first")
        assertNull(state(microphone = MicrophonePermission.Denied, recording = true).microphoneHint, "the mark says it while recording")
        assertNull(state(mode = CaptureMode.Photo, microphone = MicrophonePermission.Denied).microphoneHint)
    }

    /** Turned off on purpose, sound is a choice the screen respects: no hint nagging to turn it back on. */
    @Test
    fun the_microphone_hint_stays_quiet_when_sound_was_turned_off() {
        assertNull(state(microphone = MicrophonePermission.Denied, soundWanted = false).microphoneHint)
        assertNull(state(microphone = MicrophonePermission.PermanentlyDenied, soundWanted = false).microphoneHint)
    }

    // --- The microphone toggle ---

    @Test
    fun the_microphone_toggle_shows_in_video_and_not_while_recording() {
        assertNotNull(state().microphoneToggle)
        assertNull(state(recording = true).microphoneToggle, "sound is chosen before a recording starts")
        assertNull(state(mode = CaptureMode.Photo).microphoneToggle, "photos have no sound")
    }

    /**
     * The toggle draws what a recording would get, not merely what was asked: crossed out while the
     * microphone is refused, whatever the toggle was set to, so it never offers sound it cannot record.
     */
    @Test
    fun the_microphone_toggle_shows_whether_the_next_recording_will_have_sound() {
        assertEquals(MicrophoneToggle(soundOn = true), state(soundWanted = true).microphoneToggle)
        assertEquals(MicrophoneToggle(soundOn = false), state(soundWanted = false).microphoneToggle)
        for (refused in MicrophonePermission.entries - MicrophonePermission.Granted) {
            assertEquals(MicrophoneToggle(soundOn = false), state(microphone = refused, soundWanted = true).microphoneToggle, "$refused")
        }
    }

    // --- The one notice line ---

    private fun withNotices(
        destination: DestinationInfo? = DestinationInfo(DestinationTitle.AllItems, 3L),
        photo: CaptureProblem? = null,
        recorder: RecorderNotice? = null,
        lane: VideoNotice? = null,
    ) = CameraState(
        destination = destination,
        permission = CameraPermission.Granted,
        availability = CameraAvailability.Available,
        capabilities = CameraCapabilities(setOf(Lens.Back), true, 1f, 8f, bothQualities),
        queue = CaptureQueueState(holding = 0, bound = 3, problem = photo),
        mode = CaptureMode.Video,
        recorder = RecorderState(notice = recorder),
        lane = VideoLaneState(notice = lane),
    )

    /**
     * One line, so one wins. An album that is gone explains every capture after it; the recording's
     * own news is the freshest, since it ends what the screen was doing; then the clip's save; then a
     * photo's.
     */
    @Test
    fun the_notice_line_shows_the_most_telling_news_first() {
        val gone = DestinationInfo(DestinationTitle.AlbumGone, null)
        assertEquals(
            CameraNotice.Photo(CameraMessage.AlbumGone),
            withNotices(destination = gone, recorder = RecorderNotice.TooShort, lane = VideoNotice.SaveFailed).notice,
        )
        assertEquals(
            CameraNotice.Recording(RecorderNotice.TooShort),
            withNotices(photo = CaptureProblem.SaveFailed, recorder = RecorderNotice.TooShort, lane = VideoNotice.SaveFailed).notice,
        )
        assertEquals(
            CameraNotice.Saving(VideoNotice.SavedUnfiled),
            withNotices(photo = CaptureProblem.SaveFailed, lane = VideoNotice.SavedUnfiled).notice,
        )
        assertEquals(CameraNotice.Photo(CameraMessage.SaveFailed), withNotices(photo = CaptureProblem.SaveFailed).notice)
        assertNull(withNotices().notice)
    }

    // --- The tile ---

    @Test
    fun the_tile_shows_the_newest_capture_and_a_ring_while_a_clip_saves() {
        val shot = LastShot(byteArrayOf(1), CaptureMode.Video)
        val saving = CameraState(
            destination = null,
            permission = CameraPermission.Granted,
            availability = CameraAvailability.Available,
            capabilities = CameraCapabilities.None,
            queue = CaptureQueueState(holding = 0, bound = 3),
            lane = VideoLaneState(busy = true, progress = 0.4f),
            lastShot = shot,
        )

        assertEquals(TileState(lastShot = shot, saving = true, progress = 0.4f), saving.tile)
        assertEquals(TileState(lastShot = null, saving = false, progress = null), state().tile)
    }

    // --- Icons that turn ---

    /** Turned against the tilt, so an icon stays upright to the person holding the phone. */
    @Test
    fun icons_turn_against_the_tilt() {
        assertEquals(IconTurn(-90f, animated = true), iconTurn(current = 0f, tilt = 90, layout = CameraLayout.Below, reducedMotion = false))
        assertEquals(IconTurn(-180f, animated = true), iconTurn(current = -90f, tilt = 180, layout = CameraLayout.Below, reducedMotion = false))
        assertEquals(IconTurn(0f, animated = true), iconTurn(current = -90f, tilt = 0, layout = CameraLayout.Below, reducedMotion = false))
    }

    /**
     * Across the wrap the short way is 90°, not 270°: from upright to a 270° tilt the icon turns a
     * quarter clockwise, and back again; the angle keeps counting rather than jumping back to 0.
     */
    @Test
    fun icons_take_the_shortest_way_round_across_the_wrap_in_both_directions() {
        assertEquals(90f, iconTurn(current = 0f, tilt = 270, layout = CameraLayout.Below, reducedMotion = false).degrees)
        assertEquals(0f, iconTurn(current = 90f, tilt = 0, layout = CameraLayout.Below, reducedMotion = false).degrees)
        // From 270° tilt (icon at 90) to 180° tilt: a quarter on, to 180, not three quarters back to -180.
        assertEquals(180f, iconTurn(current = 90f, tilt = 180, layout = CameraLayout.Below, reducedMotion = false).degrees)
        // Several turns in: keeps counting from where it is.
        assertEquals(360f, iconTurn(current = 450f, tilt = 0, layout = CameraLayout.Below, reducedMotion = false).degrees)
    }

    @Test
    fun under_reduced_motion_icons_snap() {
        assertEquals(IconTurn(-90f, animated = false), iconTurn(current = 0f, tilt = 90, layout = CameraLayout.Below, reducedMotion = true))
    }

    /** Where the window is wide the display itself rotates, so icons drawn upright stay upright. */
    @Test
    fun in_the_wide_fallback_icons_do_not_turn() {
        assertEquals(IconTurn(0f, animated = false), iconTurn(current = -90f, tilt = 90, layout = CameraLayout.Beside, reducedMotion = false))
    }
}
