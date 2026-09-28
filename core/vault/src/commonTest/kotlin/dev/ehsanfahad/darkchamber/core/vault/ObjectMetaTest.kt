package dev.ehsanfahad.darkchamber.core.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ObjectMeta`'s value semantics and its log safety — both of which stop being free the moment a
 * `ByteArray` joins the class.
 *
 * **Equality has teeth here.** `VaultItemSummary` compares its `meta`, and the listing re-emits on
 * every catalog change; identity-based array comparison would make two decodes of an *unchanged*
 * row look different, so an unrelated import would redraw every tile and replay the arrival
 * animation. That is the exact bug `VaultItemSummary` avoids for its own `id`, one field further in.
 */
class ObjectMetaTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun two_metas_with_equal_key_bytes_are_equal() {
        val a = ObjectMeta("holiday.jpg", "image/jpeg", key)
        val b = ObjectMeta("holiday.jpg", "image/jpeg", key.copyOf())

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode(), "equal values must not land in different buckets")
    }

    @Test
    fun metas_differing_only_in_key_bytes_are_not_equal() {
        val a = ObjectMeta("holiday.jpg", "image/jpeg", key)
        val b = ObjectMeta("holiday.jpg", "image/jpeg", ByteArray(32) { 9 })

        assertFalse(a == b, "a different key is a different thumbnail; equality must see it")
    }

    @Test
    fun a_present_key_and_an_absent_one_are_not_equal() {
        val withKey = ObjectMeta("holiday.jpg", "image/jpeg", key)
        val without = ObjectMeta("holiday.jpg", "image/jpeg")

        assertFalse(withKey == without)
        assertFalse(without == withKey)
    }

    @Test
    fun toString_reveals_neither_the_name_nor_the_key() {
        val text = ObjectMeta("holiday.jpg", "image/jpeg", key).toString()

        // Hard rule 4: a filename is content. Hard rule 5: key material never reaches a log.
        assertFalse(text.contains("holiday"), "the decrypted name must not reach a log")
        assertFalse(text.contains("jpg"), "nor any fragment of it")
        // The generated toString would print `[B@1f2a3b` — no bytes, but it also would not say
        // whether a thumbnail exists, which is the only part worth logging.
        assertTrue(text.contains("thumbnailKey=present"), "presence is the loggable part")
    }

    @Test
    fun toString_says_when_there_is_no_thumbnail() {
        val text = ObjectMeta("note.txt", "text/plain").toString()

        assertTrue(text.contains("thumbnailKey=absent"))
        assertFalse(text.contains("note"))
    }
}
