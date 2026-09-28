package dev.ehsanfahad.darkchamber.app.thumbnail

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What can honestly be asserted about the Android encoder off-device: the MIME gate, which runs
 * before anything touches the platform decoder.
 *
 * The decode itself cannot be tested here — `BitmapFactory` is a stub in host unit tests, and a
 * test that mocked it would be asserting against the mock rather than against Android's decoder.
 * Real photos producing recognisable thumbnails is a device check, and it is listed as one in the
 * verification manual rather than pretended at here.
 *
 * What *is* reachable is whether the bytes were handed to the decoder at all, which is the whole
 * content of the gate. Declining has to be checked against accepting: "a PDF returns null" is true
 * of a gate that rejects everything, so on its own it pins nothing.
 */
class AndroidThumbnailEncoderTest {
    @Test
    fun a_type_that_is_not_an_image_is_declined_without_reading_the_file() {
        var opens = 0
        val encoder = AndroidThumbnailEncoder()

        val result = encoder.encode("application/pdf", maxEdgePx = 512) {
            opens++
            Buffer()
        }

        assertNull(result)
        // Not incidental: most of a vault is not an image, and every one of those imports would
        // otherwise pay for a full extra read of the payload to learn what the MIME already said.
        assertEquals(0, opens, "a declined type must not cost a read of the payload")
    }

    @Test
    fun an_image_type_is_carried_as_far_as_the_platform_decoder() {
        var opens = 0
        val encoder = AndroidThumbnailEncoder()

        // Upper case on purpose: a provider that reports `IMAGE/JPEG` must not be mistaken for a
        // type we cannot draw. The outcome is deliberately ignored — off-device `BitmapFactory`
        // throws, and with default-returning stubs it would answer null — because the assertion is
        // that the bytes reached it at all, which holds either way.
        runCatching { encoder.encode("IMAGE/JPEG", maxEdgePx = 512) { opens++; Buffer() } }

        assertEquals(1, opens, "an image type must reach the decoder")
    }
}
