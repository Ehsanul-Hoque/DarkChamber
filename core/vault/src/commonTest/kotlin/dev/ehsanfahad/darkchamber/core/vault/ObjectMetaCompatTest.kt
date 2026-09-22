package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `ObjectMeta` as a later cycle might extend it: today's fields plus one that carries a default.
 * Declared in the test source precisely because the point is to exercise a field `main` does not
 * have yet.
 */
@Serializable
private data class ExtendedMeta(val name: String, val mime: String, val width: Int? = null)

/**
 * `ObjectMeta` as it was **before** the thumbnail key existed — the exact shape every vault created
 * up to now has its metadata sealed in. Stored bytes are the one thing a later build cannot change,
 * so this is what "old vaults always open" means concretely.
 */
@Serializable
private data class PreThumbnailMeta(val name: String, val mime: String)

/**
 * The `enc_meta` blob is **versionless** — unlike `.dchm` it has no prelude, no format vector and
 * no field to bump — so the only thing standing between a metadata extension (dimensions, duration:
 * the richer model a later cycle adds once decoders exist) and every vault already on a device is
 * the CBOR configuration. Both directions have to hold, and neither is exercised by the
 * round-trip tests, which only ever encode and decode the same shape:
 *
 * - **old bytes, new build** — metadata sealed today must open after a field is added;
 * - **new bytes, old build** — a downgrade, or a second device a version behind, must still read
 *   metadata written by the newer build rather than failing the item as unreadable.
 *
 * The second direction is the one with teeth: an unknown key is a decode error by default, and a
 * decode error here surfaces as "can't be verified" on a perfectly intact item.
 */
@OptIn(ExperimentalSerializationApi::class)
class ObjectMetaCompatTest {
    @Test
    fun bytes_sealed_today_decode_after_a_defaulted_field_is_added() {
        // Sealed by the current build, through the module's own encoder.
        val today = encodeMeta(ObjectMeta("vacation.jpg", "image/jpeg"))

        // Opened by a build whose ObjectMeta has grown a field — same configuration, wider shape.
        val decoded = metaCbor.decodeFromByteArray<ExtendedMeta>(today)

        assertEquals("vacation.jpg", decoded.name)
        assertEquals("image/jpeg", decoded.mime)
        assertNull(decoded.width, "a field absent from stored bytes must fall back to its default")
    }

    @Test
    fun bytes_carrying_an_unknown_field_decode_instead_of_throwing() {
        // Sealed by a newer build that populates a field this one has never heard of.
        val newer = metaCbor.encodeToByteArray(ExtendedMeta("clip.mp4", "video/mp4", width = 1920))

        // decodeMeta, not a locally-built Cbor: the test has to pin the real decode path.
        val decoded = decodeMeta(newer)

        assertEquals(ObjectMeta("clip.mp4", "video/mp4"), decoded)
    }

    @Test
    fun metadata_sealed_before_thumbnails_existed_opens_with_no_thumbnail_key() {
        // Bytes as a pre-thumbnail build wrote them: two fields, nothing else.
        val sealedBefore = metaCbor.encodeToByteArray(PreThumbnailMeta("holiday.jpg", "image/jpeg"))

        val decoded = decodeMeta(sealedBefore)

        assertEquals("holiday.jpg", decoded.name)
        assertEquals("image/jpeg", decoded.mime)
        // Absent key ⇒ no thumbnail. This is the whole no-backfill story: every item imported before
        // this cycle lands here and renders its type badge, rather than failing to open.
        assertNull(decoded.thumbnailKey, "a pre-thumbnail blob must decode with the key absent")
    }

    @Test
    fun metadata_carrying_a_thumbnail_key_round_trips_it_by_content() {
        val key = ByteArray(32) { it.toByte() }

        val decoded = decodeMeta(encodeMeta(ObjectMeta("holiday.jpg", "image/jpeg", key)))

        // contentEquals, not identity: the decoded array is necessarily a different object, and a
        // key that came back altered would decrypt nothing.
        assertContentEquals(key, decoded.thumbnailKey)
    }

    @Test
    fun a_build_predating_the_thumbnail_key_still_reads_metadata_that_carries_one() {
        // The downgrade direction, now with a field that actually exists rather than a stand-in:
        // a second device one version behind must still list the item, not fail it as unreadable.
        val newer = encodeMeta(ObjectMeta("holiday.jpg", "image/jpeg", ByteArray(32) { 7 }))

        val decodedByOlderShape = metaCbor.decodeFromByteArray<PreThumbnailMeta>(newer)

        assertEquals(PreThumbnailMeta("holiday.jpg", "image/jpeg"), decodedByOlderShape)
    }
}
