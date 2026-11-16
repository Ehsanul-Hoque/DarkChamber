package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind.Audio
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind.Video
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation.Landscape
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a video page and an audio page show around the picture. */
class PlayerChromeTest {
    @Test
    fun an_audio_pages_controls_stay_on_screen_whatever_a_tap_did() {
        assertTrue(PlayerChrome.barsVisible(Audio, shownByTap = false))
        assertTrue(PlayerChrome.barsVisible(Audio, shownByTap = true))
    }

    @Test
    fun a_video_or_any_other_pages_bars_follow_the_tap() {
        assertFalse(PlayerChrome.barsVisible(Video, shownByTap = false))
        assertTrue(PlayerChrome.barsVisible(Video, shownByTap = true))
        assertFalse(PlayerChrome.barsVisible(null, shownByTap = false), "a photo")
    }

    @Test
    fun only_a_video_page_has_the_orientation_button() {
        assertEquals(Landscape, PlayerChrome.orientationButton(Video, Landscape))
        assertNull(PlayerChrome.orientationButton(Audio, Landscape))
        assertNull(PlayerChrome.orientationButton(Video, null), "no button where the screen cannot turn")
    }

    @Test
    fun only_a_playing_video_hides_its_controls_by_itself() {
        assertTrue(PlayerChrome.autoHides(Video, PlayerState(playing = true)))
        assertFalse(PlayerChrome.autoHides(Video, PlayerState(playing = false)), "paused: the controls wait")
        assertFalse(PlayerChrome.autoHides(Video, PlayerState(playing = true, ended = true)), "ended: replay stays")
        assertFalse(PlayerChrome.autoHides(Video, null), "no player yet")
        assertFalse(PlayerChrome.autoHides(Audio, PlayerState(playing = true)))
        assertFalse(PlayerChrome.autoHides(null, PlayerState(playing = true)))
    }

    @Test
    fun an_audio_page_shows_its_type_badge_where_a_video_would_be() {
        val page = ViewerPage.Media(Audio)
        assertEquals("AUD", ViewerFormat.pageBadge(page, GalleryItemMeta.Readable("voice.m4a", "audio/mp4")))
        assertNull(ViewerFormat.pageMessage(page), "a playing page carries no message")
    }
}
