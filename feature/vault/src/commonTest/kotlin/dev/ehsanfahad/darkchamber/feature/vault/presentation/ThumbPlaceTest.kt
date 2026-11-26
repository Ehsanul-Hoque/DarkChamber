package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A text page's thumb, pinned where it stands while the column's height estimate is corrected. */
class ThumbPlaceTest {
    private val pin = ThumbAnchor(scrolledPx = 400f, fraction = 0.6f)

    private fun near(
        expected: Float,
        actual: Float,
        what: String = "",
    ) = assertTrue(abs(expected - actual) < 1e-3f, "$what expected $expected, was $actual")

    @Test
    fun with_no_pin_it_is_the_fraction_of_pixels() {
        near(0.25f, ThumbPlace.fraction(250f, 1000f, null))
        near(250f, ThumbPlace.scrolledAt(0.25f, 1000f, null))
    }

    @Test
    fun the_pin_itself_is_where_the_thumb_stands() {
        near(0.6f, ThumbPlace.fraction(400f, 1000f, pin))
        near(400f, ThumbPlace.scrolledAt(0.6f, 1000f, pin))
    }

    /** Below the pin, the rest of the track spreads over the rest of the column. */
    @Test
    fun below_the_pin_it_moves_in_proportion_to_the_end() {
        near(0.8f, ThumbPlace.fraction(700f, 1000f, pin))
        near(700f, ThumbPlace.scrolledAt(0.8f, 1000f, pin))
    }

    @Test
    fun above_the_pin_it_moves_in_proportion_to_the_top() {
        near(0.3f, ThumbPlace.fraction(200f, 1000f, pin))
        near(200f, ThumbPlace.scrolledAt(0.3f, 1000f, pin))
    }

    @Test
    fun the_top_is_0_and_the_end_is_1_whatever_the_pin() {
        for (anchor in listOf(null, pin, ThumbAnchor(0f, 0.4f), ThumbAnchor(1000f, 0.4f))) {
            near(0f, ThumbPlace.fraction(0f, 1000f, anchor), "top, $anchor:")
            near(0f, ThumbPlace.fraction(-5f, 1000f, anchor), "above the top, $anchor:")
            near(1f, ThumbPlace.fraction(1000f, 1000f, anchor), "end, $anchor:")
            near(1f, ThumbPlace.fraction(1200f, 1000f, anchor), "past the end, $anchor:")
            near(0f, ThumbPlace.scrolledAt(0f, 1000f, anchor), "drag to the top, $anchor:")
            near(1000f, ThumbPlace.scrolledAt(1f, 1000f, anchor), "drag to the end, $anchor:")
        }
    }

    /** A drag lands where the thumb shows: every point, both sides of the pin. */
    @Test
    fun a_drag_is_the_inverse_of_the_place() {
        for (scrolled in listOf(0f, 1f, 100f, 399f, 400f, 401f, 550f, 999f, 1000f)) {
            near(
                scrolled,
                ThumbPlace.scrolledAt(ThumbPlace.fraction(scrolled, 1000f, pin), 1000f, pin),
                "at $scrolled:",
            )
        }
    }

    @Test
    fun a_column_that_does_not_scroll_stands_at_the_top() {
        near(0f, ThumbPlace.fraction(0f, 0f, pin))
        near(0f, ThumbPlace.scrolledAt(0.5f, 0f, pin))
    }

    /**
     * A pin at either end, or past a column that has shrunk under it, cannot share out the rest of the
     * track: the place is the plain fraction of pixels.
     */
    @Test
    fun a_pin_at_or_past_an_end_is_not_used() {
        for (anchor in listOf(
            ThumbAnchor(0f, 0.4f),
            ThumbAnchor(1000f, 0.4f),
            ThumbAnchor(1200f, 0.4f),
            ThumbAnchor(400f, 0f),
            ThumbAnchor(400f, 1f),
        )) {
            near(0.5f, ThumbPlace.fraction(500f, 1000f, anchor), "$anchor:")
            near(500f, ThumbPlace.scrolledAt(0.5f, 1000f, anchor), "$anchor:")
        }
    }

    @Test
    fun a_shown_thumb_is_pinned_where_it_stood() {
        assertEquals(
            ThumbAnchor(scrolledPx = 450f, fraction = 0.6f),
            ThumbPlace.repin(shown = true, shownBefore = 0.6f, scrolledAfter = 450f),
        )
        assertNull(ThumbPlace.repin(shown = false, shownBefore = 0.6f, scrolledAfter = 450f))
    }

    /**
     * The whole point: an estimate corrected under a shown thumb — both the distance scrolled and the
     * column's height change — leaves the thumb exactly where it was.
     */
    @Test
    fun a_corrected_estimate_does_not_move_the_thumb() {
        // Before: 400 px down a column scrolling 1,000 px, pinned earlier at (300, 0.25).
        val before = ThumbPlace.fraction(400f, 1000f, ThumbAnchor(300f, 0.25f))
        // A row above was taller than guessed (scrolled 400 → 520) and rows below too (1,000 → 3,000).
        val after = ThumbPlace.repin(shown = true, shownBefore = before, scrolledAfter = 520f)
        near(before, ThumbPlace.fraction(520f, 3000f, after))
        // And scrolling on from there moves it on, smoothly, to 1 at the new end.
        assertTrue(ThumbPlace.fraction(600f, 3000f, after) > before)
        near(1f, ThumbPlace.fraction(3000f, 3000f, after))
    }
}
