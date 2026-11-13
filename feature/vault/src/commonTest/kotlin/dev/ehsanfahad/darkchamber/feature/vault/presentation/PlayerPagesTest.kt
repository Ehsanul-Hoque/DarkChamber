package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerFailure
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerTracks
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport.Absent
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport.Playable
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport.Unplayable
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a player page shows when playback will not go, or goes only in part. The page is decided by
 * where the failure came from, never by guessing: only the vault's own integrity check calls a file
 * unverified.
 */
class PlayerPagesTest {
    @Test
    fun a_read_the_vault_refused_as_damaged_is_unverified() {
        assertEquals(ViewerPage.Unverified, pageForPlayerFailure(PlayerFailure.Reader(VaultError.Crypto.IntegrityFailure)))
        assertEquals(ViewerPage.Unverified, pageForPlayerFailure(PlayerFailure.Reader(VaultError.Crypto.MalformedContainer)))
    }

    @Test
    fun a_read_of_a_newer_format_asks_for_an_update() {
        assertEquals(
            ViewerPage.NeedsUpdate,
            pageForPlayerFailure(PlayerFailure.Reader(VaultError.Crypto.UnsupportedFormatVersion)),
        )
    }

    @Test
    fun a_decoder_that_declines_the_format_is_cant_play_never_damage() {
        assertEquals(ViewerPage.CantPlay, pageForPlayerFailure(PlayerFailure.Decoder))
    }

    @Test
    fun any_other_failed_read_or_failure_names_no_cause() {
        assertEquals(ViewerPage.CouldntOpen, pageForPlayerFailure(PlayerFailure.Reader(VaultError.Io(RuntimeException("x")))))
        assertEquals(ViewerPage.CouldntOpen, pageForPlayerFailure(PlayerFailure.Other))
    }

    @Test
    fun a_stop_or_a_lock_is_no_page_at_all() {
        assertNull(pageForPlayerFailure(PlayerFailure.Stopped))
    }

    private fun tracks(video: TrackSupport, audio: TrackSupport) = PlayerTracks(video = video, audio = audio)

    @Test
    fun a_picture_this_device_cannot_play_under_sound_it_can_says_the_picture_is_missing() {
        assertEquals(TrackNote.NoPicture, trackNote(tracks(Unplayable, Playable)))
        assertFalse(nothingPlays(tracks(Unplayable, Playable)), "the sound plays")
    }

    @Test
    fun sound_this_device_cannot_play_under_a_picture_it_can_says_the_sound_is_missing() {
        assertEquals(TrackNote.NoSound, trackNote(tracks(Playable, Unplayable)))
        assertFalse(nothingPlays(tracks(Playable, Unplayable)), "the picture plays")
    }

    @Test
    fun everything_found_playing_needs_no_note() {
        assertNull(trackNote(tracks(Playable, Playable)))
        assertFalse(nothingPlays(tracks(Playable, Playable)))
    }

    @Test
    fun a_track_the_item_does_not_have_is_not_missing() {
        assertNull(trackNote(tracks(Absent, Playable)), "an audio file has no picture to miss")
        assertNull(trackNote(tracks(Playable, Absent)), "a silent clip has no sound to miss")
        assertFalse(nothingPlays(tracks(Absent, Playable)))
        assertFalse(nothingPlays(tracks(Playable, Absent)))
    }

    @Test
    fun nothing_this_device_can_play_is_the_whole_cant_play_page_not_a_note() {
        for (found in listOf(tracks(Unplayable, Unplayable), tracks(Unplayable, Absent), tracks(Absent, Unplayable))) {
            assertTrue(nothingPlays(found), "$found")
            assertNull(trackNote(found), "$found: the page says it, not a note")
        }
    }

    @Test
    fun before_any_track_is_known_there_is_neither_a_note_nor_a_page() {
        assertNull(trackNote(tracks(Absent, Absent)))
        assertFalse(nothingPlays(tracks(Absent, Absent)))
    }
}
