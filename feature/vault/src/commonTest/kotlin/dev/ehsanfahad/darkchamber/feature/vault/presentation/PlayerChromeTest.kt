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

    /**
     * Someone moving through the controls with a screen reader takes longer than three seconds, and
     * the controls vanishing under them strands them: they stay up while a screen reader runs.
     */
    @Test
    fun a_screen_reader_keeps_a_playing_videos_controls_up() {
        assertFalse(PlayerChrome.autoHides(Video, PlayerState(playing = true), screenReader = true))
        assertTrue(PlayerChrome.autoHides(Video, PlayerState(playing = true), screenReader = false))
    }

    /**
     * Both are centred on the page, so unmoved the centre control covers the badge. Moved by the shift,
     * the badge's bottom edge is exactly the gap above the control's top edge. Positions are measured
     * from the page's middle, downward positive, as the layout places them.
     */
    @Test
    fun the_audio_badge_sits_a_gap_above_the_centre_control() {
        for ((badge, centre, gap) in listOf(Triple(100, 252, 40), Triple(73, 126, 21), Triple(260, 252, 56))) {
            val shift = PlayerChrome.badgeShiftAboveCentre(badge, centre, gap)
            val badgeBottom = shift + badge / 2 + badge % 2 // centred: top at -badge/2, so bottom at +ceil
            val centreTop = -centre / 2
            assertEquals(centreTop - gap, badgeBottom, "badge $badge, centre $centre, gap $gap")
        }
    }

    @Test
    fun a_phone_on_its_side_gets_the_one_row_bar_and_nothing_taller_does() {
        assertTrue(PlayerChrome.compactBar(411f), "a large phone in landscape")
        assertTrue(PlayerChrome.compactBar(479.9f))
        assertFalse(PlayerChrome.compactBar(480f), "the compact-height boundary itself is not compact")
        assertFalse(PlayerChrome.compactBar(891f), "the same phone upright")
        assertFalse(PlayerChrome.compactBar(800f), "a tablet in landscape")
    }

    @Test
    fun a_screen_reader_can_skip_on_a_playing_page_and_show_or_hide_a_videos_controls() {
        assertEquals(
            listOf(ReaderAction.ToggleControls, ReaderAction.Back, ReaderAction.Forward),
            PlayerChrome.readerActions(Video, hasPlayer = true),
        )
        assertEquals(
            listOf(ReaderAction.Back, ReaderAction.Forward),
            PlayerChrome.readerActions(Audio, hasPlayer = true),
        )
    }

    @Test
    fun with_no_player_there_is_nothing_to_skip() {
        assertEquals(listOf(ReaderAction.ToggleControls), PlayerChrome.readerActions(Video, hasPlayer = false))
        assertEquals(emptyList(), PlayerChrome.readerActions(Audio, hasPlayer = false))
    }

    @Test
    fun an_audio_page_shows_its_type_badge_where_a_video_would_be() {
        val page = ViewerPage.Media(Audio)
        assertEquals("AUD", ViewerFormat.pageBadge(page, GalleryItemMeta.Readable("voice.m4a", "audio/mp4")))
        assertNull(ViewerFormat.pageMessage(page), "a playing page carries no message")
    }
}
