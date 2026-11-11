package dev.ehsanfahad.darkchamber.app.image

import kotlin.test.Test
import kotlin.test.assertNull

/**
 * What can honestly be checked off-device: that the decoder keeps its promise never to throw.
 *
 * Off-device `BitmapFactory` is a stub that throws on every call, so this is a decoder whose platform
 * refuses everything — the shape of a format it cannot read, or an allocation that fails. Real photos
 * decoding upright and subsampled is a device check (the nine EXIF orientations and a 48 MP photo),
 * done with the viewer drawn, and not pretended at here.
 */
class AndroidViewerImageDecoderTest {
    @Test
    fun a_platform_that_refuses_the_bytes_is_a_decline_not_a_throw() {
        assertNull(AndroidViewerImageDecoder().decode(byteArrayOf(1, 2, 3), targetLongEdge = 4_800))
    }
}
