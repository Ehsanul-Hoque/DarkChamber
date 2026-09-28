package dev.ehsanfahad.darkchamber.app.thumbnail

import androidx.exifinterface.media.ExifInterface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How an EXIF orientation tag turns into the transform a preview needs.
 *
 * A phone held upright writes the pixels in the sensor's own landscape order and records
 * `ORIENTATION_ROTATE_90` beside them, so every viewer that honours the tag shows the photo the
 * right way up — and `BitmapFactory`, which does not, hands back a bitmap lying on its side. The
 * preview is then compressed to JPEG, which carries no EXIF at all, so the tag is not merely
 * ignored here: it is gone, and nothing downstream can recover it.
 *
 * Applying the transform is device work. Choosing it is arithmetic, and this is the arithmetic.
 */
class ThumbnailOrientationTest {

    @Test
    fun an_upright_photo_needs_no_transform() {
        val normal = orientationTransform(ExifInterface.ORIENTATION_NORMAL)

        assertEquals(0, normal.degrees)
        assertFalse(normal.mirrored)
        assertFalse(normal.swapsEdges)
    }

    @Test
    fun a_missing_or_unknown_tag_is_treated_as_upright() {
        // Most images have no orientation at all — every PNG, every screenshot, anything a
        // scrubber has already been through. Guessing at one would rotate them wrongly.
        assertEquals(0, orientationTransform(ExifInterface.ORIENTATION_UNDEFINED).degrees)
        assertEquals(0, orientationTransform(0).degrees)
        assertEquals(0, orientationTransform(99).degrees)
    }

    @Test
    fun the_three_rotations_are_read_as_themselves() {
        assertEquals(90, orientationTransform(ExifInterface.ORIENTATION_ROTATE_90).degrees)
        assertEquals(180, orientationTransform(ExifInterface.ORIENTATION_ROTATE_180).degrees)
        assertEquals(270, orientationTransform(ExifInterface.ORIENTATION_ROTATE_270).degrees)
    }

    @Test
    fun a_quarter_turn_swaps_which_edge_is_the_long_one() {
        // The whole reason this feeds the sizing: a 4000x3000 sensor frame tagged ROTATE_90 is a
        // 3000x4000 picture, and a target computed from the sensor's own numbers would crop the
        // wrong edge to fit the tile.
        assertTrue(orientationTransform(ExifInterface.ORIENTATION_ROTATE_90).swapsEdges)
        assertTrue(orientationTransform(ExifInterface.ORIENTATION_ROTATE_270).swapsEdges)
        assertTrue(orientationTransform(ExifInterface.ORIENTATION_TRANSPOSE).swapsEdges)
        assertTrue(orientationTransform(ExifInterface.ORIENTATION_TRANSVERSE).swapsEdges)
        // A half turn does not: the picture is the same shape upside down.
        assertFalse(orientationTransform(ExifInterface.ORIENTATION_ROTATE_180).swapsEdges)
    }

    @Test
    fun the_mirrored_orientations_are_carried_rather_than_rounded_off() {
        // Rare, and produced by real cameras all the same — a selfie saved without re-encoding.
        // Dropping the flip would hand back a preview that is a mirror of the file it came from.
        val horizontal = orientationTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertTrue(horizontal.mirrored)
        assertEquals(0, horizontal.degrees)

        val vertical = orientationTransform(ExifInterface.ORIENTATION_FLIP_VERTICAL)
        assertTrue(vertical.mirrored)
        assertEquals(180, vertical.degrees, "a vertical flip is a mirror plus a half turn")

        // The pairing these two get wrong if you go by the names: **transpose is the 270° one** and
        // transverse is the 90° one. The first version of this test asserted the opposite, which is
        // also what the code did, so the two agreed and both were wrong — a device run drew tags 5
        // and 7 upside down. The values below are the EXIF table's, checked against real files
        // whose rasters are transposed so that an honoured tag renders every variant identically.
        val transpose = orientationTransform(ExifInterface.ORIENTATION_TRANSPOSE)
        assertTrue(transpose.mirrored)
        assertEquals(270, transpose.degrees)

        val transverse = orientationTransform(ExifInterface.ORIENTATION_TRANSVERSE)
        assertTrue(transverse.mirrored)
        assertEquals(90, transverse.degrees)
    }

    @Test
    fun only_formats_that_carry_the_tag_are_opened_for_it() {
        // Reading it costs an extra open of the source, which for a file a cloud provider
        // materialises on demand is not free. PNG and WebP as this app writes and receives them do
        // not carry one, so the read would be a round-trip spent to learn nothing.
        assertTrue(carriesOrientation("image/jpeg"))
        assertTrue(carriesOrientation("image/heic"))
        assertTrue(carriesOrientation("IMAGE/JPEG"), "the MIME is the platform's, not ours to trust the case of")
        assertFalse(carriesOrientation("image/png"))
        assertFalse(carriesOrientation("image/gif"))
    }
}
