package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
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
}
