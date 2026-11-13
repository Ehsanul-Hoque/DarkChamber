package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The player's seek, speed and end-of-media rules. Expected values are what the user sees after the
 * gesture — "ten seconds back from 0:04 is 0:00" — worked by hand, not from the formula.
 */
class PlaybackRulesTest {
    private val skip = PlaybackRules.SKIP_MS

    @Test
    fun a_double_tap_moves_ten_seconds_either_way() {
        assertEquals(52_000L, PlaybackRules.skipped(42_000L, skip, 111_000L), "right half: 0:42 → 0:52")
        assertEquals(32_000L, PlaybackRules.skipped(42_000L, -skip, 111_000L), "left half: 0:42 → 0:32")
    }

    @Test
    fun ten_seconds_back_near_the_start_stops_at_the_start() {
        assertEquals(0L, PlaybackRules.skipped(4_000L, -skip, 111_000L))
        assertEquals(0L, PlaybackRules.skipped(0L, -skip, 111_000L))
    }

    @Test
    fun ten_seconds_forward_near_the_end_stops_at_the_end() {
        assertEquals(111_000L, PlaybackRules.skipped(105_000L, skip, 111_000L))
        assertEquals(111_000L, PlaybackRules.skipped(111_000L, skip, 111_000L))
    }

    @Test
    fun forward_with_the_end_not_yet_known_is_not_held_back() {
        assertEquals(52_000L, PlaybackRules.skipped(42_000L, skip, null))
        assertEquals(0L, PlaybackRules.skipped(3_000L, -skip, null), "the start is always known")
    }

    @Test
    fun holding_a_half_runs_at_its_speed_whatever_was_set() {
        assertEquals(2f, PlaybackRules.runningSpeed(1.25f, HeldSpeed.Fast), "right half")
        assertEquals(0.5f, PlaybackRules.runningSpeed(1.25f, HeldSpeed.Slow), "left half")
    }

    @Test
    fun letting_go_returns_to_the_speed_that_was_set() {
        assertEquals(1.25f, PlaybackRules.runningSpeed(1.25f, null))
        assertEquals(1.5f, PlaybackRules.runningSpeed(1.5f, null))
    }

    @Test
    fun the_speed_setting_offers_half_to_double_with_normal_among_them() {
        assertEquals(listOf(0.5f, 1f, 1.25f, 1.5f, 2f), PlaybackRules.SPEEDS)
        assertEquals(1f, PlaybackRules.NORMAL_SPEED)
    }

    @Test
    fun the_centre_control_pauses_a_playing_item_plays_a_paused_one_and_replays_a_finished_one() {
        assertEquals(CentreControl.Pause, PlaybackRules.centreControl(PlaybackStatus.Playing))
        assertEquals(CentreControl.Play, PlaybackRules.centreControl(PlaybackStatus.Paused))
        assertEquals(CentreControl.Replay, PlaybackRules.centreControl(PlaybackStatus.Ended))
    }

    @Test
    fun only_a_playing_item_keeps_the_screen_on() {
        assertTrue(PlaybackRules.keepsScreenOn(PlaybackStatus.Playing))
        assertFalse(PlaybackRules.keepsScreenOn(PlaybackStatus.Paused))
        assertFalse(PlaybackRules.keepsScreenOn(PlaybackStatus.Ended), "a finished item lets the screen sleep")
    }
}

/**
 * What the viewer remembers between pages while it is open. A new memory stands for a newly opened
 * viewer — closing one or a lock discards the old memory with it.
 */
class PlaybackMemoryTest {
    private val clip = byteArrayOf(1, 2, 3)
    private val song = byteArrayOf(9, 8, 7)

    @Test
    fun an_item_never_played_starts_at_the_beginning() {
        assertEquals(0L, PlaybackMemory().startPosition(clip))
    }

    @Test
    fun coming_back_to_an_item_resumes_where_it_was_left() {
        val memory = PlaybackMemory()
        memory.leave(clip, positionMs = 42_000L, durationMs = 111_000L, ended = false)
        assertEquals(42_000L, memory.startPosition(clip))
    }

    @Test
    fun the_position_is_found_by_the_ids_content_not_the_array_it_came_in() {
        val memory = PlaybackMemory()
        memory.leave(clip, positionMs = 42_000L, durationMs = 111_000L, ended = false)
        assertEquals(42_000L, memory.startPosition(clip.copyOf()))
    }

    @Test
    fun each_item_keeps_its_own_position() {
        val memory = PlaybackMemory()
        memory.leave(clip, positionMs = 42_000L, durationMs = 111_000L, ended = false)
        memory.leave(song, positionMs = 62_000L, durationMs = 310_000L, ended = false)
        assertEquals(42_000L, memory.startPosition(clip))
        assertEquals(62_000L, memory.startPosition(song))
    }

    @Test
    fun leaving_again_replaces_the_earlier_position() {
        val memory = PlaybackMemory()
        memory.leave(clip, positionMs = 42_000L, durationMs = 111_000L, ended = false)
        memory.leave(clip, positionMs = 50_000L, durationMs = 111_000L, ended = false)
        assertEquals(50_000L, memory.startPosition(clip))
    }

    @Test
    fun an_item_that_played_to_its_end_starts_again_from_the_beginning() {
        val memory = PlaybackMemory()
        memory.leave(clip, positionMs = 42_000L, durationMs = 111_000L, ended = false)
        // A player reports the end at its last frame, which can be a little short of the duration.
        memory.leave(clip, positionMs = 110_960L, durationMs = 111_000L, ended = true)
        assertEquals(0L, memory.startPosition(clip))
    }

    @Test
    fun a_position_left_at_the_end_starts_again_even_without_the_ended_signal() {
        val memory = PlaybackMemory()
        memory.leave(clip, positionMs = 111_000L, durationMs = 111_000L, ended = false)
        assertEquals(0L, memory.startPosition(clip))
    }

    @Test
    fun a_position_that_makes_no_sense_starts_at_the_beginning() {
        val memory = PlaybackMemory()
        memory.leave(clip, positionMs = -5L, durationMs = 111_000L, ended = false)
        assertEquals(0L, memory.startPosition(clip))
    }

    @Test
    fun a_position_with_the_end_unknown_is_kept() {
        val memory = PlaybackMemory()
        memory.leave(clip, positionMs = 42_000L, durationMs = null, ended = false)
        assertEquals(42_000L, memory.startPosition(clip))
    }

    @Test
    fun a_newly_opened_viewer_remembers_nothing() {
        val first = PlaybackMemory()
        first.leave(clip, positionMs = 42_000L, durationMs = 111_000L, ended = false)
        first.chooseSpeed(1.5f)
        val reopened = PlaybackMemory()
        assertEquals(0L, reopened.startPosition(clip))
        assertEquals(1f, reopened.speed)
    }

    @Test
    fun the_speed_starts_normal_and_a_chosen_one_carries_to_every_item() {
        val memory = PlaybackMemory()
        assertEquals(1f, memory.speed)
        memory.chooseSpeed(1.5f)
        assertEquals(1.5f, memory.speed)
        memory.chooseSpeed(0.5f)
        assertEquals(0.5f, memory.speed)
    }

    @Test
    fun a_speed_the_setting_does_not_offer_is_ignored() {
        val memory = PlaybackMemory()
        memory.chooseSpeed(1.25f)
        memory.chooseSpeed(3f)
        assertEquals(1.25f, memory.speed, "3× is not a choice")
        memory.chooseSpeed(0f)
        assertEquals(1.25f, memory.speed, "nor is stopped")
    }
}
