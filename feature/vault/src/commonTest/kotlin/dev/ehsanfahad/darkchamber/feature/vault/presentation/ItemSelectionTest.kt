package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The selection: a set of ids and a mode flag, with two traps in it.
 *
 * **The key type.** `ByteArray` hashes and compares by identity, so a `Set<ByteArray>` takes the
 * same id twice without noticing and every lookup against a freshly decoded id misses. Both
 * failures are silent — a count one too high, and a selected tile that will not deselect — which is
 * why the duplicate case is tested with two *distinct arrays* holding the same bytes rather than the
 * same instance twice. Passing the same instance twice would pass under either implementation.
 *
 * **The live list.** Items leave the listing while selected: shredded here or elsewhere, or moved
 * out of the album being viewed. A selection that kept them would report a count that is wrong and
 * hand an action ids that no longer exist.
 */
class ItemSelectionTest {

    private fun item(vararg id: Byte) = GalleryItem(
        id = byteArrayOf(*id),
        kind = VaultItemKind.FILE,
        size = 10L,
        createdAt = 1L,
        meta = GalleryItemMeta.Readable("file.txt", "text/plain"),
    )

    @Test
    fun a_long_press_enters_the_mode_with_that_item_selected() {
        val selection = ItemSelection()

        selection.start(byteArrayOf(1))

        assertTrue(selection.state.value.active)
        assertEquals(1, selection.state.value.count)
        assertTrue(selection.state.value.contains(byteArrayOf(1)), "found by content, not by instance")
    }

    /**
     * The `ByteArray` trap, from both sides: adding the same id twice selects one thing, and asking
     * about it with a different array that holds the same bytes finds it.
     */
    @Test
    fun the_same_id_in_a_different_array_is_the_same_item() {
        val selection = ItemSelection()

        selection.start(byteArrayOf(1, 2, 3))
        selection.toggle(byteArrayOf(1, 2, 3)) // a distinct array, the same id

        assertEquals(0, selection.state.value.count, "the second press deselected it rather than adding a twin")

        selection.toggle(byteArrayOf(1, 2, 3))
        selection.toggle(byteArrayOf(1, 2, 3))
        assertEquals(0, selection.state.value.count, "and it keeps toggling the one item, never accumulating")
    }

    @Test
    fun toggling_adds_and_removes_without_leaving_the_mode() {
        val selection = ItemSelection()
        selection.start(byteArrayOf(1))

        selection.toggle(byteArrayOf(2))
        assertEquals(2, selection.state.value.count)

        selection.toggle(byteArrayOf(1))
        assertEquals(1, selection.state.value.count)
        assertTrue(selection.state.value.active, "an empty-ish selection is still selecting")
        assertFalse(selection.state.value.contains(byteArrayOf(1)))
    }

    @Test
    fun toggling_outside_the_mode_does_nothing() {
        val selection = ItemSelection()

        selection.toggle(byteArrayOf(1))

        assertFalse(selection.state.value.active, "a tap is not a long-press")
        assertEquals(0, selection.state.value.count)
    }

    /**
     * Select all means everything on screen, which is the filtered list. Structural rather than
     * enforced: this type only ever sees what the gallery gave it, so inside an album it cannot
     * reach past that album.
     */
    @Test
    fun select_all_takes_the_current_list_not_the_whole_vault() {
        val selection = ItemSelection()
        // What an album's filter would emit: a subset of the vault.
        selection.onItems(listOf(item(1), item(2)))
        selection.start(byteArrayOf(1))

        selection.selectAll()

        assertEquals(2, selection.state.value.count)
        assertTrue(selection.state.value.contains(byteArrayOf(2)))
        assertFalse(
            selection.state.value.contains(byteArrayOf(9)),
            "an item the screen is not showing cannot be selected by Select all",
        )
    }

    @Test
    fun select_all_follows_the_list_it_was_last_given() {
        val selection = ItemSelection()
        selection.onItems(listOf(item(1), item(2), item(3)))
        selection.start(byteArrayOf(1))

        // The filter changed under it — fewer items on screen now.
        selection.onItems(listOf(item(1)))
        selection.selectAll()

        assertEquals(1, selection.state.value.count, "all of what is showing, not all of what was")
    }

    @Test
    fun select_all_outside_the_mode_does_nothing() {
        val selection = ItemSelection()
        selection.onItems(listOf(item(1), item(2)))

        selection.selectAll()

        assertFalse(selection.state.value.active)
        assertEquals(0, selection.state.value.count)
    }

    @Test
    fun clearing_exits_the_mode_and_drops_the_selection() {
        val selection = ItemSelection()
        selection.start(byteArrayOf(1))
        selection.toggle(byteArrayOf(2))

        selection.clear()

        assertFalse(selection.state.value.active, "Clear and Back both leave the mode")
        assertEquals(0, selection.state.value.count)
    }

    /**
     * The live-list intersection. The item was selected and then shredded — on this device or
     * another — and the selection must not keep it: the count would overstate what an action will
     * touch, and the action would write against a row that is gone.
     */
    @Test
    fun an_item_that_leaves_the_list_leaves_the_selection() {
        val selection = ItemSelection()
        selection.onItems(listOf(item(1), item(2)))
        selection.start(byteArrayOf(1))
        selection.toggle(byteArrayOf(2))
        assertEquals(2, selection.state.value.count)

        selection.onItems(listOf(item(2)))

        assertEquals(1, selection.state.value.count, "the shredded item dropped out")
        assertFalse(selection.state.value.contains(byteArrayOf(1)))
        assertTrue(selection.state.value.contains(byteArrayOf(2)), "its neighbour is untouched")
    }

    /**
     * Moving an item out of the album being viewed is the same event as it being shredded, as far as
     * this screen can tell: it is no longer in the list, so it is no longer selectable.
     */
    @Test
    fun an_intersection_that_empties_the_selection_keeps_the_mode() {
        val selection = ItemSelection()
        selection.onItems(listOf(item(1)))
        selection.start(byteArrayOf(1))

        selection.onItems(emptyList())

        assertEquals(0, selection.state.value.count)
        assertTrue(
            selection.state.value.active,
            "the user did not ask to leave — an empty bar with Select all is better than the mode vanishing",
        )
    }

    @Test
    fun an_emission_that_changes_nothing_leaves_the_state_identical() {
        val selection = ItemSelection()
        selection.onItems(listOf(item(1), item(2)))
        selection.start(byteArrayOf(1))
        val before = selection.state.value

        selection.onItems(listOf(item(1), item(2)))

        assertEquals(before, selection.state.value, "a re-emission of an unchanged list is not a change")
    }

    @Test
    fun selected_ids_come_back_as_byte_arrays_in_selection_order() {
        val selection = ItemSelection()
        selection.start(byteArrayOf(3))
        selection.toggle(byteArrayOf(1))
        selection.toggle(byteArrayOf(2))

        val ids = selection.selectedIds()

        assertEquals(3, ids.size)
        assertContentEquals(byteArrayOf(3), ids[0], "the order they were picked, not hash order")
        assertContentEquals(byteArrayOf(1), ids[1])
        assertContentEquals(byteArrayOf(2), ids[2])
    }

    @Test
    fun start_while_already_selecting_behaves_as_a_toggle() {
        val selection = ItemSelection()
        selection.start(byteArrayOf(1))

        // A second long-press, on an already-selected tile: callers should not have to branch on a
        // mode they are already in.
        selection.start(byteArrayOf(1))

        assertEquals(0, selection.state.value.count)
        assertTrue(selection.state.value.active)
    }
}
