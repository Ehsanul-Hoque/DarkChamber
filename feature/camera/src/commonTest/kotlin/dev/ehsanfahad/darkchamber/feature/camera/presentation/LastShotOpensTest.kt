package dev.ehsanfahad.darkchamber.feature.camera.presentation

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingProgress
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import dev.ehsanfahad.darkchamber.feature.camera.domain.ActiveRecording
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureQueueState
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationInfo
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationTitle
import dev.ehsanfahad.darkchamber.feature.camera.domain.RecorderState
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoLaneState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the last-shot tile opens the viewer. Opening it covers the camera, and a covered camera releases
 * the hardware — which fails any photo still in the platform's hands and ends a recording. So it opens
 * only when there is a shot, nothing is recording and no photo is capturing. Each fixture below differs
 * from the one that opens in exactly one term.
 */
class LastShotOpensTest {
    private fun state(
        hasShot: Boolean = true,
        recording: Boolean = false,
        capturing: Int = 0,
        laneBusy: Boolean = false,
    ) = CameraState(
        destination = DestinationInfo(DestinationTitle.AllItems, 3L),
        permission = CameraPermission.Granted,
        availability = CameraAvailability.Available,
        capabilities = CameraCapabilities(setOf(Lens.Back), true, 1f, 8f, setOf(VideoQuality.HD)),
        queue = CaptureQueueState(holding = capturing, bound = 3, capturing = capturing),
        mode = if (recording) CaptureMode.Video else CaptureMode.Photo,
        recorder = RecorderState(
            active = if (recording) ActiveRecording(1_000, true, MutableStateFlow(RecordingProgress.Zero)) else null,
        ),
        lane = VideoLaneState(busy = laneBusy),
        lastShot = if (hasShot) LastShot(byteArrayOf(7), CaptureMode.Photo) else null,
    )

    @Test
    fun a_shot_with_nothing_capturing_or_recording_opens() {
        assertTrue(state().lastShotOpens)
    }

    @Test
    fun no_shot_yet_opens_nothing() {
        assertFalse(state(hasShot = false).lastShotOpens)
    }

    /** Covering the camera would end the clip. */
    @Test
    fun not_while_recording() {
        assertFalse(state(recording = true).lastShotOpens)
    }

    /** Covering the camera would fail the photo the platform still holds. */
    @Test
    fun not_while_a_photo_is_capturing() {
        assertFalse(state(capturing = 1).lastShotOpens)
    }

    /** A clip *saving* runs in the session's lane, which covering the camera does not touch. */
    @Test
    fun a_clip_still_saving_does_not_stop_it() {
        assertTrue(state(laneBusy = true).lastShotOpens)
    }
}
