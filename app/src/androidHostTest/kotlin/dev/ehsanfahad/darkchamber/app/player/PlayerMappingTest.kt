package dev.ehsanfahad.darkchamber.app.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.ehsanfahad.darkchamber.app.player.PlayerMapping.FoundTrack
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerFailure
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerTracks
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Android player's decisions, without a device. */
class PlayerMappingTest {
    // ---- where a failure came from -------------------------------------------------------------

    @Test
    fun a_read_the_vault_refused_is_the_vaults_verdict_whatever_the_player_called_it() {
        val refused = VaultError.Crypto.IntegrityFailure
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )) {
            assertEquals(PlayerFailure.Reader(refused), PlayerMapping.failureOf(code, refused), "code $code")
        }
    }

    @Test
    fun a_format_this_device_cannot_handle_with_no_refused_read_is_the_decoders() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )) {
            assertEquals(PlayerFailure.Decoder, PlayerMapping.failureOf(code, null), "code $code")
        }
    }

    @Test
    fun anything_else_names_no_cause() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )) {
            assertEquals(PlayerFailure.Other, PlayerMapping.failureOf(code, null), "code $code")
        }
    }

    // ---- the tracks ----------------------------------------------------------------------------

    private val video = C.TRACK_TYPE_VIDEO
    private val audio = C.TRACK_TYPE_AUDIO

    @Test
    fun a_clip_whose_picture_and_sound_both_play() {
        assertEquals(
            PlayerTracks(TrackSupport.Playable, TrackSupport.Playable),
            PlayerMapping.tracksOf(listOf(FoundTrack(video, true), FoundTrack(audio, true))),
        )
    }

    @Test
    fun a_picture_this_device_cannot_decode_under_sound_it_can() {
        assertEquals(
            PlayerTracks(TrackSupport.Unplayable, TrackSupport.Playable),
            PlayerMapping.tracksOf(listOf(FoundTrack(video, false), FoundTrack(audio, true))),
        )
    }

    @Test
    fun a_kind_with_no_tracks_is_absent() {
        assertEquals(
            PlayerTracks(TrackSupport.Absent, TrackSupport.Playable),
            PlayerMapping.tracksOf(listOf(FoundTrack(audio, true))),
        )
        assertEquals(PlayerTracks(), PlayerMapping.tracksOf(emptyList()))
    }

    @Test
    fun one_playable_track_among_several_makes_the_kind_playable() {
        val found = listOf(FoundTrack(audio, false), FoundTrack(audio, true), FoundTrack(video, true))

        assertEquals(TrackSupport.Playable, PlayerMapping.tracksOf(found).audio)
    }

    @Test
    fun subtitles_and_metadata_are_not_counted_as_picture_or_sound() {
        val found =
            listOf(
                FoundTrack(C.TRACK_TYPE_TEXT, false),
                FoundTrack(C.TRACK_TYPE_METADATA, false),
                FoundTrack(audio, true),
            )

        assertEquals(PlayerTracks(TrackSupport.Absent, TrackSupport.Playable), PlayerMapping.tracksOf(found))
    }

    // ---- the read-ahead cap --------------------------------------------------------------------

    @Test
    fun the_player_reads_ahead_ten_to_fifteen_seconds_and_no_more() {
        assertEquals(10_000, PlayerMapping.MIN_BUFFER_MS)
        assertEquals(15_000, PlayerMapping.MAX_BUFFER_MS)
        assertTrue(PlayerMapping.BUFFER_FOR_PLAYBACK_MS <= PlayerMapping.MIN_BUFFER_MS)
        assertTrue(PlayerMapping.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS <= PlayerMapping.MIN_BUFFER_MS)
    }

    // ---- the sound taken away ------------------------------------------------------------------

    @Test
    fun another_app_taking_the_sound_for_a_while_pauses_for_good() {
        assertTrue(PlayerMapping.pausesFor(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS))
    }

    @Test
    fun nothing_else_the_player_holds_back_for_is_a_pause() {
        assertFalse(PlayerMapping.pausesFor(Player.PLAYBACK_SUPPRESSION_REASON_NONE))
        assertFalse(PlayerMapping.pausesFor(Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT))
    }

    // ---- volume steps ----------------------------------------------------------------------------

    @Test
    fun a_level_becomes_the_nearest_volume_step() {
        assertEquals(0, PlayerMapping.volumeStep(0f, 15))
        assertEquals(15, PlayerMapping.volumeStep(1f, 15))
        assertEquals(8, PlayerMapping.volumeStep(0.5f, 15), "7.5 rounds up")
        assertEquals(3, PlayerMapping.volumeStep(0.2f, 15))
    }

    @Test
    fun a_level_outside_nothing_to_full_is_held_to_it() {
        assertEquals(0, PlayerMapping.volumeStep(-0.3f, 15))
        assertEquals(15, PlayerMapping.volumeStep(1.4f, 15))
    }

    @Test
    fun a_volume_step_is_its_share_of_the_most() {
        assertEquals(0f, PlayerMapping.volumeLevel(0, 15))
        assertEquals(1f, PlayerMapping.volumeLevel(15, 15))
        assertEquals(0.2f, PlayerMapping.volumeLevel(3, 15), 0.0001f)
        assertEquals(0f, PlayerMapping.volumeLevel(5, 0), "a phone reporting no steps")
    }
}
