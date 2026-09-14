package dev.ehsanfahad.darkchamber.app.picker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Registry keys must be unique among **live** registrations: `ActivityResultRegistry.register`
 * throws on a key already in use, and a crash is a poor way to discover that two pickers overlapped.
 *
 * A counter rather than a hash of anything — hashes repeat — and one counter shared by both pickers,
 * so a document pick and a photo pick in flight together cannot collide either.
 */
class PickerRequestKeyTest {
    @Test
    fun keys_from_one_prefix_never_repeat() {
        val keys = List(100) { nextPickerRequestKey("darkchamber-import") }

        assertEquals(100, keys.toSet().size, "every key is distinct")
        assertTrue(keys.all { it.startsWith("darkchamber-import") }, "the prefix says which picker")
    }

    @Test
    fun the_two_pickers_share_one_counter_so_their_keys_cannot_collide() {
        val mixed = List(50) { nextPickerRequestKey(if (it % 2 == 0) "photos" else "import") }

        assertEquals(50, mixed.toSet().size, "interleaved pickers still get distinct keys")
    }
}
