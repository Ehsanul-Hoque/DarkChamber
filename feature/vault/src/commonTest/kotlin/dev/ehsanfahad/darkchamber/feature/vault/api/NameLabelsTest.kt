package dev.ehsanfahad.darkchamber.feature.vault.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one place a name that will not open is put into words.
 *
 * It was spelled out at four call sites before this, which is why it is worth a test of its own now:
 * the wording is a claim bounded by `docs/THREAT_MODEL.md`, and a single point of decision is only an
 * improvement if that point is the one being checked.
 */
class NameLabelsTest {

    @Test
    fun a_readable_album_name_is_itself() {
        assertEquals("Berlin trip", AlbumTileName.Readable("Berlin trip").label())
    }

    @Test
    fun a_readable_item_name_is_itself() {
        assertEquals("tax.pdf", GalleryItemMeta.Readable("tax.pdf", "application/pdf").label())
    }

    /**
     * **It says what happened and stops.** A failed GCM tag is all that is known: corruption and
     * tampering are indistinguishable from here, so naming either would be a guess presented as a
     * finding (hard rule 6). Asserted as an absence, because that is the way this gets broken —
     * someone makes the sentence more helpful.
     */
    @Test
    fun an_unreadable_name_names_no_cause() {
        val copy = AlbumTileName.Unreadable.label()

        assertTrue("verif" in copy.lowercase(), "it says the name did not verify: $copy")
        for (guess in listOf("corrupt", "tamper", "damaged", "modified", "attack", "deleted", "virus")) {
            assertFalse(guess in copy.lowercase(), "a cause we cannot know: $copy")
        }
    }

    /**
     * And it is the **same sentence** for an item as for an album. A user who has met it on a tile
     * should not have to learn a second phrase for the same condition one screen over — which is the
     * drift four separate copies of it were one edit away from.
     */
    @Test
    fun an_album_and_an_item_say_it_the_same_way() {
        assertEquals(AlbumTileName.Unreadable.label(), GalleryItemMeta.Unreadable.label())
    }
}
