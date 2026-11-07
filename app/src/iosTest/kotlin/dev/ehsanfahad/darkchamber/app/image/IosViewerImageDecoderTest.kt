package dev.ehsanfahad.darkchamber.app.image

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS viewer decoder against **the real ImageIO**. Expected pictures come from
 * [ViewerDecodeFixtures] — made and checked by other tools — never from this decoder's own idea of
 * what an orientation means.
 */
class IosViewerImageDecoderTest {
    private val decoder = IosViewerImageDecoder()

    private enum class Quadrant(val color: Color) { RED(Color.Red), GREEN(Color.Green), BLUE(Color.Blue), YELLOW(Color.Yellow) }

    /** The quadrant colour nearest the pixel at a fraction of the image's width and height. */
    private fun ImageBitmap.quadrantAt(fx: Float, fy: Float): Quadrant {
        val pixel = toPixelMap()[(width * fx).toInt(), (height * fy).toInt()]
        return Quadrant.entries.minBy { q ->
            abs(q.color.red - pixel.red) + abs(q.color.green - pixel.green) + abs(q.color.blue - pixel.blue)
        }
    }

    /** Sampled away from the generator's labels: they sit mid-edge and in the centre. */
    private fun ImageBitmap.corners() = listOf(
        quadrantAt(0.2f, 0.2f),
        quadrantAt(0.8f, 0.2f),
        quadrantAt(0.2f, 0.8f),
        quadrantAt(0.8f, 0.8f),
    )

    private val upright = listOf(Quadrant.RED, Quadrant.GREEN, Quadrant.BLUE, Quadrant.YELLOW)

    @Test
    fun every_exif_orientation_decodes_upright() {
        ViewerDecodeFixtures.ORIENTED.forEachIndexed { tag, jpeg ->
            val image = assertNotNull(decoder.decode(jpeg, targetLongEdge = 1_000), "orientation $tag decodes")
            assertEquals(
                ViewerDecodeFixtures.WIDTH to ViewerDecodeFixtures.HEIGHT,
                image.width to image.height,
                "orientation $tag: dimensions as seen",
            )
            assertEquals(upright, image.corners(), "orientation $tag: quadrants where the source had them")
        }
    }

    /** The camera's own photos are HEIC on an iPhone — the reason this is not the grid's Skia decoder. */
    @Test
    fun a_heic_image_decodes() {
        val image = assertNotNull(decoder.decode(ViewerDecodeFixtures.HEIC, targetLongEdge = 1_000))
        assertEquals(ViewerDecodeFixtures.WIDTH to ViewerDecodeFixtures.HEIGHT, image.width to image.height)
        assertEquals(upright, image.corners())
    }

    @Test
    fun the_long_edge_is_at_most_the_target_with_the_shape_kept() {
        val image = assertNotNull(decoder.decode(ViewerDecodeFixtures.ORIENTED[6], targetLongEdge = 16))
        assertEquals(16 to 8, image.width to image.height, "a quarter-turned photo is sized as seen")
    }

    @Test
    fun a_small_image_is_never_enlarged() {
        val image = assertNotNull(decoder.decode(ViewerDecodeFixtures.ORIENTED[1], targetLongEdge = 4_800))
        assertEquals(ViewerDecodeFixtures.WIDTH to ViewerDecodeFixtures.HEIGHT, image.width to image.height)
    }

    @Test
    fun bytes_that_are_not_an_image_are_declined() {
        assertNull(decoder.decode(byteArrayOf(1, 2, 3, 4, 5), targetLongEdge = 1_000))
        assertNull(decoder.decode(ByteArray(0), targetLongEdge = 1_000))
    }

    /** The caller zeroes the array once this returns; the decoder must not have changed it before then. */
    @Test
    fun the_input_is_left_as_it_was() {
        val jpeg = ViewerDecodeFixtures.ORIENTED[3]
        val before = jpeg.copyOf()
        decoder.decode(jpeg, targetLongEdge = 1_000)
        assertTrue(before.contentEquals(jpeg))
    }
}
