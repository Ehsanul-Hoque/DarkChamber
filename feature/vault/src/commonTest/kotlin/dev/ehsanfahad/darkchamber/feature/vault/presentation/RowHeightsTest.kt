package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

/** A column's height from the items laid out so far, and the item at a point down it. */
class RowHeightsTest {

    @Test
    fun nothing_laid_out_is_nothing_known() {
        val h = RowHeights(10)
        assertEquals(0f, h.average())
        assertEquals(0f, h.total())
        assertEquals(RowHeights.Spot(0, 0), h.at(50f))
    }

    @Test
    fun known_heights_add_up_exactly() {
        val h = RowHeights(3)
        h.record(0, 10)
        h.record(1, 20)
        h.record(2, 30)
        assertEquals(listOf(0f, 10f, 30f, 60f), (0..3).map { h.top(it) })
        assertEquals(60f, h.total())
        assertEquals(20f, h.average())
    }

    /** An item not laid out counts as the average of those that have been. */
    @Test
    fun unknown_items_count_as_the_average() {
        val h = RowHeights(6)
        h.record(0, 10)
        h.record(1, 30)
        // Average 20: items 2..5 are estimated at 20 each.
        assertEquals(40f, h.top(2))
        assertEquals(80f, h.top(4))
        assertEquals(120f, h.total())
    }

    @Test
    fun a_later_height_replaces_an_earlier_one() {
        val h = RowHeights(2)
        h.record(0, 10)
        h.record(0, 50)
        h.record(1, 50)
        assertEquals(100f, h.total())
        assertEquals(50f, h.average())
    }

    /** What moved the thumb: the count of items on screen. Here nothing depends on it. */
    @Test
    fun a_known_column_does_not_move_as_items_are_seen_again() {
        val h = RowHeights(4)
        listOf(10, 200, 10, 200).forEachIndexed(h::record)
        val before = (0..4).map { h.top(it) }
        listOf(10, 200, 10, 200).forEachIndexed(h::record)
        assertEquals(before, (0..4).map { h.top(it) })
    }

    @Test
    fun the_item_at_a_point_and_how_far_into_it() {
        val h = RowHeights(3)
        listOf(10, 20, 30).forEachIndexed(h::record)
        assertEquals(RowHeights.Spot(0, 0), h.at(0f))
        assertEquals(RowHeights.Spot(0, 9), h.at(9f))
        assertEquals(RowHeights.Spot(1, 0), h.at(10f))
        assertEquals(RowHeights.Spot(2, 5), h.at(35f))
    }

    @Test
    fun past_either_end_is_the_first_or_the_last_item() {
        val h = RowHeights(3)
        listOf(10, 20, 30).forEachIndexed(h::record)
        assertEquals(RowHeights.Spot(0, 0), h.at(-5f))
        assertEquals(RowHeights.Spot(2, 29), h.at(500f))
    }

    @Test
    fun the_item_at_a_point_among_estimated_items() {
        val h = RowHeights(10)
        h.record(0, 10)
        h.record(1, 30)
        // Items from 2 on are 20 each: 40 + 3 * 20 = 100 is where item 5 starts.
        assertEquals(RowHeights.Spot(5, 0), h.at(100f))
        assertEquals(RowHeights.Spot(5, 7), h.at(107f))
    }

    /** A height from outside the column, or a negative one, changes nothing and throws nothing. */
    @Test
    fun a_height_out_of_range_is_ignored() {
        val h = RowHeights(2)
        h.record(-1, 10)
        h.record(2, 10)
        h.record(0, -5)
        assertEquals(0f, h.total())
        h.record(0, 10)
        assertEquals(20f, h.total())
    }

    @Test
    fun a_top_asked_outside_the_column_is_its_start_or_its_end() {
        val h = RowHeights(3)
        listOf(10, 20, 30).forEachIndexed(h::record)
        assertEquals(0f, h.top(-1))
        assertEquals(60f, h.top(7))
    }

    /** A million rows: recording and asking stay cheap and right. */
    @Test
    fun a_large_column_adds_up() {
        val n = 1_000_000
        val h = RowHeights(n)
        for (i in 0 until n step 2) h.record(i, 30)
        for (i in 1 until n step 2) h.record(i, 10)
        assertEquals(20_000_000f, h.total())
        assertEquals(10_000_000f, h.top(n / 2))
        assertEquals(RowHeights.Spot(n / 2, 5), h.at(10_000_005f))
    }
}
