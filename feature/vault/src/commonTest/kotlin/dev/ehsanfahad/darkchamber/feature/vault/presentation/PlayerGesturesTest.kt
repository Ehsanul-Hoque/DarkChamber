package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The player page's gesture map. A page 1 000 wide; the band between the bars runs from 200 to 1 700,
 * and the seek bar sits in the bottom bar, below the band.
 */
class PlayerGesturesTest {
    private val seekBar = Rect(left = 40f, top = 1_720f, right = 960f, bottom = 1_760f)
    private val area = GestureArea(width = 1_000f, bandTop = 200f, bandBottom = 1_700f, seekBar = seekBar)
    private val controlsHidden = area.copy(seekBar = null)

    private val left = Offset(250f, 900f)
    private val right = Offset(750f, 900f)
    private val up = Offset(2f, -30f)
    private val down = Offset(-2f, 30f)
    private val sideways = Offset(30f, 2f)

    @Test
    fun the_page_splits_down_the_middle() {
        assertEquals(Half.Left, PlayerGestures.half(0f, 1_000f))
        assertEquals(Half.Left, PlayerGestures.half(499f, 1_000f))
        assertEquals(Half.Right, PlayerGestures.half(500f, 1_000f), "the middle line is the right's")
        assertEquals(Half.Right, PlayerGestures.half(999f, 1_000f))
    }

    @Test
    fun a_tap_shows_or_hides_a_videos_controls_but_an_audio_pages_stay() {
        assertEquals(GestureAction.ToggleControls, PlayerGestures.onTap(PlayerKind.Video))
        assertEquals(GestureAction.None, PlayerGestures.onTap(PlayerKind.Audio))
    }

    @Test
    fun a_double_tap_goes_back_ten_seconds_on_the_left_and_forward_on_the_right() {
        assertEquals(GestureAction.Skip(-10_000L), PlayerGestures.onDoubleTap(left.x, area))
        assertEquals(GestureAction.Skip(10_000L), PlayerGestures.onDoubleTap(right.x, area))
    }

    @Test
    fun holding_plays_slower_on_the_left_and_faster_on_the_right() {
        assertEquals(GestureAction.Hold(HeldSpeed.Slow), PlayerGestures.onHold(left.x, area))
        assertEquals(GestureAction.Hold(HeldSpeed.Fast), PlayerGestures.onHold(right.x, area))
    }

    @Test
    fun on_a_video_a_slide_on_the_left_is_brightness_and_on_the_right_volume() {
        assertEquals(DragOwner.Brightness, PlayerGestures.dragOwner(PlayerKind.Video, left, up, area))
        assertEquals(DragOwner.Brightness, PlayerGestures.dragOwner(PlayerKind.Video, left, down, area))
        assertEquals(DragOwner.Volume, PlayerGestures.dragOwner(PlayerKind.Video, right, up, area))
        assertEquals(DragOwner.Volume, PlayerGestures.dragOwner(PlayerKind.Video, right, down, area))
    }

    @Test
    fun an_audio_page_has_no_brightness_and_the_pager_does_not_take_that_slide_either() {
        assertEquals(DragOwner.Nobody, PlayerGestures.dragOwner(PlayerKind.Audio, left, up, area))
        assertEquals(DragOwner.Volume, PlayerGestures.dragOwner(PlayerKind.Audio, right, up, area))
    }

    @Test
    fun a_drag_that_starts_sideways_turns_the_page_on_either_half_and_either_kind() {
        assertEquals(DragOwner.Pager, PlayerGestures.dragOwner(PlayerKind.Video, left, sideways, area))
        assertEquals(DragOwner.Pager, PlayerGestures.dragOwner(PlayerKind.Video, right, -sideways, area))
        assertEquals(DragOwner.Pager, PlayerGestures.dragOwner(PlayerKind.Audio, left, sideways, area))
    }

    @Test
    fun a_slanted_drag_goes_the_way_it_leans_most_and_an_even_one_turns_the_page() {
        assertEquals(DragOwner.Pager, PlayerGestures.dragOwner(PlayerKind.Video, right, Offset(30f, 20f), area))
        assertEquals(DragOwner.Volume, PlayerGestures.dragOwner(PlayerKind.Video, right, Offset(20f, -30f), area))
        assertEquals(DragOwner.Pager, PlayerGestures.dragOwner(PlayerKind.Video, right, Offset(25f, -25f), area))
    }

    @Test
    fun a_slide_that_starts_outside_the_band_does_nothing() {
        val aboveBand = Offset(750f, 199f)
        val belowBand = Offset(750f, 1_700f)
        assertEquals(DragOwner.Nobody, PlayerGestures.dragOwner(PlayerKind.Video, aboveBand, down, controlsHidden))
        assertEquals(DragOwner.Nobody, PlayerGestures.dragOwner(PlayerKind.Video, belowBand, up, controlsHidden))
        assertEquals(DragOwner.Brightness, PlayerGestures.dragOwner(PlayerKind.Video, Offset(250f, 200f), down, controlsHidden), "the band's top edge is inside")
    }

    @Test
    fun a_sideways_drag_outside_the_band_still_turns_the_page() {
        assertEquals(DragOwner.Pager, PlayerGestures.dragOwner(PlayerKind.Video, Offset(500f, 100f), sideways, area))
    }

    @Test
    fun a_drag_that_starts_on_the_seek_bar_belongs_to_it_whichever_way_it_goes() {
        val onBar = Offset(500f, 1_740f)
        assertEquals(DragOwner.SeekBar, PlayerGestures.dragOwner(PlayerKind.Video, onBar, sideways, area))
        assertEquals(DragOwner.SeekBar, PlayerGestures.dragOwner(PlayerKind.Video, onBar, up, area))
        assertEquals(DragOwner.SeekBar, PlayerGestures.dragOwner(PlayerKind.Audio, onBar, -sideways, area))
    }

    @Test
    fun with_the_controls_hidden_the_seek_bars_place_turns_the_page_like_anywhere_else() {
        val whereTheBarWas = Offset(500f, 1_740f)
        assertEquals(DragOwner.Pager, PlayerGestures.dragOwner(PlayerKind.Video, whereTheBarWas, sideways, controlsHidden))
    }

    @Test
    fun sliding_the_bands_whole_height_up_goes_from_nothing_to_full() {
        assertEquals(1f, PlayerGestures.levelAfterSlide(0f, -1_500f, area))
        assertEquals(0f, PlayerGestures.levelAfterSlide(1f, 1_500f, area))
    }

    @Test
    fun a_shorter_slide_moves_the_level_in_proportion_up_raises_and_down_lowers() {
        assertEquals(0.7f, PlayerGestures.levelAfterSlide(0.5f, -300f, area), 0.0001f)
        assertEquals(0.3f, PlayerGestures.levelAfterSlide(0.5f, 300f, area), 0.0001f)
    }

    @Test
    fun the_level_stays_between_nothing_and_full() {
        assertEquals(1f, PlayerGestures.levelAfterSlide(0.9f, -750f, area))
        assertEquals(0f, PlayerGestures.levelAfterSlide(0.1f, 750f, area))
    }

    @Test
    fun a_band_with_no_height_leaves_the_level_alone() {
        val flat = area.copy(bandTop = 500f, bandBottom = 500f)
        assertEquals(0.4f, PlayerGestures.levelAfterSlide(0.4f, -300f, flat))
    }
}
