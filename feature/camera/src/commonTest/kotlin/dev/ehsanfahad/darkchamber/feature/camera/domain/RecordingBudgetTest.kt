package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingBudgetTest {

    /**
     * The clip and its encrypted copy both need the disk until the encrypt ends, so the clip may
     * take only half of what is left after the reserve — otherwise a full-length clip would be
     * recorded and then could not be saved.
     */
    @Test
    fun the_limit_is_half_the_space_left_after_the_reserve() {
        val free = RECORDING_RESERVE_BYTES + 2_000_000_000L

        assertEquals(RecordingRoom.Limit(1_000_000_000L), recordingRoom(free))
    }

    @Test
    fun an_odd_remainder_rounds_down_so_the_limit_never_exceeds_half() {
        val free = RECORDING_RESERVE_BYTES + 2 * RECORDING_FLOOR_BYTES + 1

        assertEquals(RecordingRoom.Limit(RECORDING_FLOOR_BYTES), recordingRoom(free))
    }

    @Test
    fun a_limit_at_the_floor_starts_and_one_byte_under_it_is_refused() {
        val atFloor = RECORDING_RESERVE_BYTES + 2 * RECORDING_FLOOR_BYTES

        assertEquals(RecordingRoom.Limit(RECORDING_FLOOR_BYTES), recordingRoom(atFloor))
        assertEquals(RecordingRoom.NotEnough, recordingRoom(atFloor - 2))
    }

    @Test
    fun less_free_space_than_the_reserve_is_refused_rather_than_a_negative_limit() {
        assertEquals(RecordingRoom.NotEnough, recordingRoom(RECORDING_RESERVE_BYTES - 1))
        assertEquals(RecordingRoom.NotEnough, recordingRoom(0))
    }

    /**
     * A platform that cannot say how much space there is gets no recording: with no limit a clip
     * could take the whole disk and leave its plaintext in `tmp/` with no room to encrypt it. And it
     * is not "not enough", which would name a cause nobody measured.
     */
    @Test
    fun unknown_free_space_is_refused_as_unknown() {
        assertEquals(RecordingRoom.Unknown, recordingRoom(null))
    }

    /** The status line's "left" is measured against the limit, so it reaches zero at the auto-stop. */
    @Test
    fun room_left_counts_down_from_the_limit_and_is_zero_exactly_at_it() {
        assertEquals(1_000L, roomLeft(limitBytes = 1_000, recordedBytes = 0))
        assertEquals(1L, roomLeft(limitBytes = 1_000, recordedBytes = 999))
        assertEquals(0L, roomLeft(limitBytes = 1_000, recordedBytes = 1_000))
    }

    /** A platform may overshoot its limit by a frame before it stops; "left" never goes negative. */
    @Test
    fun room_left_is_never_negative() {
        assertEquals(0L, roomLeft(limitBytes = 1_000, recordedBytes = 1_400))
    }

    @Test
    fun sound_is_on_only_with_the_microphone_granted_and_the_hint_follows_what_would_help() {
        assertEquals(RecordingSound(withAudio = true, hint = MicrophoneHint.None), recordingSound(MicrophonePermission.Granted, soundWanted = true))
        assertEquals(RecordingSound(withAudio = false, hint = MicrophoneHint.Ask), recordingSound(MicrophonePermission.NotAsked, soundWanted = true))
        assertEquals(RecordingSound(withAudio = false, hint = MicrophoneHint.Ask), recordingSound(MicrophonePermission.Denied, soundWanted = true))
        assertEquals(
            RecordingSound(withAudio = false, hint = MicrophoneHint.Settings),
            recordingSound(MicrophonePermission.PermanentlyDenied, soundWanted = true),
        )
    }

    /**
     * Sound turned off is a choice, not a problem: no audio whatever the permission, and no hint
     * telling the person how to get back what they just turned off.
     */
    @Test
    fun sound_turned_off_records_without_it_whatever_the_permission_and_gives_no_hint() {
        for (permission in MicrophonePermission.entries) {
            assertEquals(
                RecordingSound(withAudio = false, hint = MicrophoneHint.None),
                recordingSound(permission, soundWanted = false),
                "with $permission",
            )
        }
    }
}
