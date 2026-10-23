package dev.ehsanfahad.darkchamber.feature.camera.presentation

import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingProgress
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationTitle
import dev.ehsanfahad.darkchamber.feature.camera.domain.MicrophoneHint
import dev.ehsanfahad.darkchamber.feature.camera.domain.RecorderNotice
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The camera's words. Each line is written from the failure path — what is still true when the
 * thing it describes has gone wrong — and the claims it must never make are asserted absent, so a
 * later rewrite cannot quietly bring one in.
 */
class CameraFormatTest {

    private val unreadable = "Can't be verified" // what the host hands in; the camera never spells it

    @Test
    fun the_title_names_the_destination() {
        assertEquals("All items", CameraFormat.title(DestinationTitle.AllItems, unreadable))
        assertEquals("Unfiled", CameraFormat.title(DestinationTitle.Unfiled, unreadable))
        assertEquals("Trip to Sylhet", CameraFormat.title(DestinationTitle.Album("Trip to Sylhet"), unreadable))
    }

    /** The damaged-name sentence is the one the rest of the app uses, handed in — never a second copy. */
    @Test
    fun an_album_whose_name_will_not_open_reads_as_the_hosts_label() {
        assertEquals("anything the host says", CameraFormat.title(DestinationTitle.UnreadableAlbum, "anything the host says"))
    }

    /** Nothing is known to name: no title rather than a guess. */
    @Test
    fun a_gone_or_unknown_destination_has_no_title() {
        assertNull(CameraFormat.title(DestinationTitle.AlbumGone, unreadable))
        assertNull(CameraFormat.title(DestinationTitle.Unknown, unreadable))
    }

    /** The same words an album tile uses under its name, so one number reads one way everywhere. */
    @Test
    fun the_count_reads_like_an_album_tile_and_is_absent_when_unknown() {
        assertEquals("Empty", CameraFormat.count(0L))
        assertEquals("1 item", CameraFormat.count(1L))
        assertEquals("2 items", CameraFormat.count(2L))
        assertEquals("1834 items", CameraFormat.count(1834L))
        assertNull(CameraFormat.count(null), "an unknown count shows nothing, never zero")
    }

    /**
     * An album deleted under the camera unfiles what was in it — so photos already saved are still
     * in the vault. It says that, and claims nothing about a photo whose save was in flight.
     */
    @Test
    fun the_album_gone_message_says_where_saved_photos_are() {
        val text = CameraFormat.message(CameraMessage.AlbumGone)
        assertTrue("Unfiled" in text, text)
        assertTrue("already saved" in text, "only saved photos are promised: $text")
    }

    @Test
    fun a_failure_names_no_cause() {
        assertEquals("Couldn't save that photo.", CameraFormat.message(CameraMessage.SaveFailed))
        assertEquals("Couldn't take that photo.", CameraFormat.message(CameraMessage.CaptureFailed))
    }

    @Test
    fun each_explainer_says_what_is_true_and_offers_only_what_works() {
        val denied = CameraFormat.explainer(CameraExplainer.Denied)!!
        assertEquals("Allow", denied.action)

        val off = CameraFormat.explainer(CameraExplainer.PermanentlyDenied)!!
        assertEquals("Open settings", off.action, "asking again would be a button that does nothing")
        assertTrue("locks" in off.text, "leaving for Settings locks the vault, and it says so: ${off.text}")

        val inUse = CameraFormat.explainer(CameraExplainer.InUse)!!
        assertEquals("Camera is in use by another app.", inUse.text)
        assertNull(inUse.action)

        val unavailable = CameraFormat.explainer(CameraExplainer.Unavailable)!!
        assertEquals("Camera isn't available right now.", unavailable.text, "no cause the platform did not give")
        assertNull(unavailable.action)

        assertNull(CameraFormat.explainer(CameraExplainer.None))
    }

    @Test
    fun the_flash_control_says_its_state_out_loud() {
        assertEquals("Flash: off", CameraFormat.flashDescription(FlashMode.Off))
        assertEquals("Flash: auto", CameraFormat.flashDescription(FlashMode.Auto))
        assertEquals("Flash: on", CameraFormat.flashDescription(FlashMode.On))
    }

    // --- Video ---

    @Test
    fun the_timer_reads_minutes_and_seconds_then_hours_past_an_hour() {
        assertEquals("00:00", CameraFormat.timer(Duration.ZERO))
        assertEquals("00:59", CameraFormat.timer(59.seconds + 999.milliseconds), "a second is shown once it has passed")
        assertEquals("01:00", CameraFormat.timer(60.seconds))
        assertEquals("59:59", CameraFormat.timer(1.hours - 1.seconds))
        assertEquals("1:00:00", CameraFormat.timer(1.hours))
        assertEquals("2:03:04", CameraFormat.timer(2.hours + 3.minutes + 4.seconds))
    }

    /**
     * Decimal units, as the phone's own storage screens use, and rounded **down**: "left" must never
     * claim room that is not there, and reads zero at the auto-stop.
     */
    @Test
    fun sizes_read_in_megabytes_then_gigabytes_rounded_down() {
        assertEquals("0 MB", CameraFormat.size(0))
        assertEquals("0 MB", CameraFormat.size(999_999))
        assertEquals("1 MB", CameraFormat.size(1_000_000))
        assertEquals("999 MB", CameraFormat.size(999_999_999))
        assertEquals("1.0 GB", CameraFormat.size(1_000_000_000))
        assertEquals("1.2 GB", CameraFormat.size(1_299_999_999))
        assertEquals("34.5 GB", CameraFormat.size(34_567_000_000))
    }

    @Test
    fun the_status_says_how_long_how_much_recorded_and_how_much_is_left_of_this_clip() {
        val status = CameraFormat.recordingStatus(RecordingProgress(1_200_000_000, 2.minutes + 14.seconds), limitBytes = 4_600_000_000)
        assertEquals("02:14 · 1.2 GB recorded · 3.4 GB left", status)
    }

    /** "Left" counts down from the limit fixed at start, never the disk's free space, and stops at zero. */
    @Test
    fun the_status_left_is_measured_against_the_limit_and_reaches_zero_at_it() {
        assertEquals("00:01 · 5 MB recorded · 0 MB left", CameraFormat.recordingStatus(RecordingProgress(5_000_000, 1.seconds), limitBytes = 5_000_000))
        assertEquals("00:01 · 6 MB recorded · 0 MB left", CameraFormat.recordingStatus(RecordingProgress(6_000_000, 1.seconds), limitBytes = 5_000_000))
    }

    /** Bytes recorded are labelled as recorded: never as the vault's size or the disk's use. */
    @Test
    fun the_status_never_calls_its_numbers_vault_or_disk_usage() {
        for (bytes in listOf(0L, 999_999L, 5_000_000_000L)) {
            val status = CameraFormat.recordingStatus(RecordingProgress(bytes, 3.minutes), limitBytes = 6_000_000_000).lowercase()
            for (word in listOf("used", "vault", "free", "storage")) assertFalse(word in status, "\"$status\" says \"$word\"")
        }
    }

    /** Sound off is not a dead end: the line says how to turn it on, and offers only what works. */
    @Test
    fun the_microphone_hint_says_how_to_record_sound_and_offers_only_what_works() {
        val ask = CameraFormat.microphoneHint(MicrophoneHint.Ask)!!
        assertEquals("Allow", ask.action)
        assertTrue("without sound" in ask.text, ask.text)

        val settings = CameraFormat.microphoneHint(MicrophoneHint.Settings)!!
        assertEquals("Open settings", settings.action, "asking again would do nothing")
        assertTrue("locks" in settings.text, "leaving for Settings locks the vault: ${settings.text}")

        assertNull(CameraFormat.microphoneHint(MicrophoneHint.None))
    }

    @Test
    fun the_recording_notices_say_what_happened() {
        assertEquals("Not enough space to record.", CameraFormat.recorderNotice(RecorderNotice.NotEnoughSpace))
        assertEquals("Too short to save.", CameraFormat.recorderNotice(RecorderNotice.TooShort))
        assertEquals("Recording stopped — not enough space for a longer clip.", CameraFormat.recorderNotice(RecorderNotice.StoppedAtLimit))
        assertEquals("Couldn't record that video.", CameraFormat.recorderNotice(RecorderNotice.RecordingFailed))
    }

    /** Free space that could not be read is not called too little: nobody measured it. */
    @Test
    fun unknown_space_is_not_called_too_little() {
        val text = CameraFormat.recorderNotice(RecorderNotice.SpaceUnknown).lowercase()
        assertFalse("not enough" in text, text)
        assertFalse("out of space" in text, text)
    }

    @Test
    fun a_full_disk_is_named_for_photos_and_video_alike_and_every_other_failure_is_not() {
        val full = "Couldn't save — your phone is out of space."
        assertEquals(full, CameraFormat.message(CameraMessage.OutOfSpace))
        assertEquals(full, CameraFormat.videoNotice(VideoNotice.OutOfSpace))
        assertEquals("Couldn't save that video.", CameraFormat.videoNotice(VideoNotice.SaveFailed))
    }

    @Test
    fun a_clip_whose_album_went_says_where_it_is() {
        assertEquals("Saved to Unfiled — its album was deleted.", CameraFormat.videoNotice(VideoNotice.SavedUnfiled))
    }

    @Test
    fun the_shutter_is_described_by_what_a_press_does_and_why_it_cannot() {
        assertEquals("Take photo", CameraFormat.shutterDescription(Shutter(ShutterKind.Photo, enabled = true)))
        assertEquals("Start recording", CameraFormat.shutterDescription(Shutter(ShutterKind.Record, enabled = true)))
        assertEquals("Stop recording", CameraFormat.shutterDescription(Shutter(ShutterKind.Stop, enabled = true)))
        assertEquals(
            "Start recording — unavailable until the last video has saved",
            CameraFormat.shutterDescription(Shutter(ShutterKind.Record, enabled = false, waitingOnSave = true)),
        )
        assertEquals(
            "Start recording",
            CameraFormat.shutterDescription(Shutter(ShutterKind.Record, enabled = false)),
            "disabled for another reason, nothing is claimed about saving — the platform reads out 'disabled'",
        )
    }

    @Test
    fun the_video_controls_say_their_state() {
        assertEquals("Torch: off", CameraFormat.torchDescription(on = false))
        assertEquals("Torch: on", CameraFormat.torchDescription(on = true))
        assertEquals("HD", CameraFormat.qualityLabel(VideoQuality.HD))
        assertEquals("4K", CameraFormat.qualityLabel(VideoQuality.UHD))
        assertEquals("Video quality: 4K", CameraFormat.qualityDescription(VideoQuality.UHD))
        assertEquals("Recording without sound", CameraFormat.noSoundDescription())
        assertEquals("Sound: on", CameraFormat.soundDescription(MicrophoneToggle(soundOn = true)))
        assertEquals("Sound: off", CameraFormat.soundDescription(MicrophoneToggle(soundOn = false)))
        assertEquals("Photo", CameraFormat.modeLabel(CaptureMode.Photo))
        assertEquals("Video", CameraFormat.modeLabel(CaptureMode.Video))
    }

    /**
     * Every line this screen can show or speak, checked for the claims it may not make:
     * - "silent" — iOS forces a shutter sound in some regions, and nothing here can stop it;
     * - "never leave" — photos and videos will be exportable;
     * - "never stored" / "unencrypted" — a video passes through one plaintext file before encryption;
     * - "wiped" / "erased" / "cleared" about memory — only this code's own arrays are zeroed;
     * - "tampered" / "corrupt" — a failed tag is all that is known about a damaged name.
     */
    @Test
    fun no_line_makes_a_claim_the_code_cannot_keep() {
        val forbidden = listOf(
            "silent", "never leave", "never stored", "unencrypted", "wiped", "erased", "cleared", "tamper", "corrupt",
        )
        for (line in everyLine() + spaceLines()) {
            for (word in forbidden) {
                assertFalse(word in line.lowercase(), "\"$line\" says \"$word\"")
            }
        }
    }

    /**
     * Space is named only where the code knows it is the cause: the vault reported the disk full,
     * the room check refused or could not be made, or the platform stopped at our limit. Every other
     * line keeps clear of it.
     */
    @Test
    fun only_the_lines_about_space_name_it() {
        for (line in everyLine()) {
            for (word in listOf("storage", "full", "space")) {
                assertFalse(word in line.lowercase(), "\"$line\" says \"$word\"")
            }
        }
    }

    // The lines that may name space, each for a cause the code knows.
    private fun spaceLines(): List<String> = listOf(
        CameraFormat.message(CameraMessage.OutOfSpace),
        CameraFormat.videoNotice(VideoNotice.OutOfSpace),
        CameraFormat.recorderNotice(RecorderNotice.NotEnoughSpace),
        CameraFormat.recorderNotice(RecorderNotice.SpaceUnknown),
        CameraFormat.recorderNotice(RecorderNotice.StoppedAtLimit),
    )

    private fun everyLine(): List<String> = buildList {
        for (title in listOf(DestinationTitle.AllItems, DestinationTitle.Unfiled, DestinationTitle.UnreadableAlbum)) {
            add(CameraFormat.title(title, unreadable)!!)
        }
        for (n in listOf(0L, 1L, 5L)) add(CameraFormat.count(n)!!)
        (CameraMessage.entries - CameraMessage.OutOfSpace).forEach { add(CameraFormat.message(it)) }
        CameraExplainer.entries.mapNotNull { CameraFormat.explainer(it) }.forEach { add(it.text); it.action?.let(::add) }
        FlashMode.entries.forEach { add(CameraFormat.flashDescription(it)) }
        for (kind in ShutterKind.entries) {
            for (enabled in listOf(true, false)) {
                for (waiting in listOf(true, false)) add(CameraFormat.shutterDescription(Shutter(kind, enabled, waiting)))
            }
        }
        add(CameraFormat.recordingStatus(RecordingProgress(1_234_567_890, 65.minutes), limitBytes = 9_000_000_000))
        MicrophoneHint.entries.mapNotNull { CameraFormat.microphoneHint(it) }.forEach { add(it.text); it.action?.let(::add) }
        (RecorderNotice.entries - setOf(RecorderNotice.NotEnoughSpace, RecorderNotice.SpaceUnknown, RecorderNotice.StoppedAtLimit))
            .forEach { add(CameraFormat.recorderNotice(it)) }
        (VideoNotice.entries - VideoNotice.OutOfSpace).forEach { add(CameraFormat.videoNotice(it)) }
        listOf(true, false).forEach { add(CameraFormat.torchDescription(it)) }
        VideoQuality.entries.forEach { add(CameraFormat.qualityLabel(it)); add(CameraFormat.qualityDescription(it)) }
        CaptureMode.entries.forEach { add(CameraFormat.modeLabel(it)) }
        add(CameraFormat.noSoundDescription())
        listOf(true, false).forEach { add(CameraFormat.soundDescription(MicrophoneToggle(it))) }
        add(CameraFormat.flipDescription())
        add(CameraFormat.closeDescription())
        add(CameraFormat.lastShotDescription(hasShot = true))
        add(CameraFormat.lastShotDescription(hasShot = false))
    }
}
