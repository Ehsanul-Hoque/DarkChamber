package dev.ehsanfahad.darkchamber.app.thumbnail

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS encoder against **the real ImageIO**, which the simulator gives us and the Android host
 * does not — so unlike its Android counterpart this covers the decode itself, not only the gate.
 *
 * Images are generated here rather than checked in as fixtures: a committed binary would have to be
 * trusted to be what it claims, and generating one means the expected dimensions are stated by the
 * test rather than by a file someone would have to open to understand.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosThumbnailEncoderTest {
    private val encoder = IosThumbnailEncoder()

    @Test
    fun a_landscape_image_is_decoded_down_to_the_limit_keeping_its_shape() {
        val jpeg = solidImageJpeg(width = 1000.0, height = 500.0)

        val thumbnail = encoder.encode("image/jpeg", maxEdgePx = 64) { jpeg.asSource() }

        val decoded = assertNotNull(thumbnail?.asUIImage(), "a JPEG must produce a thumbnail")
        assertEquals(64.0, decoded.size.useContents { width }, "longest edge must hit the limit")
        assertEquals(32.0, decoded.size.useContents { height }, "aspect ratio must survive")
        assertTrue(thumbnail.size < jpeg.size, "a thumbnail that is not smaller is pointless")
    }

    @Test
    fun a_portrait_image_scales_by_its_height() {
        val jpeg = solidImageJpeg(width = 500.0, height = 1000.0)

        val thumbnail = encoder.encode("image/jpeg", maxEdgePx = 64) { jpeg.asSource() }

        val decoded = assertNotNull(thumbnail?.asUIImage())
        assertEquals(32.0, decoded.size.useContents { width })
        assertEquals(64.0, decoded.size.useContents { height })
    }

    @Test
    fun an_image_smaller_than_the_limit_comes_back_at_its_own_size() {
        val png = solidImagePng(width = 40.0, height = 20.0)

        val thumbnail = encoder.encode("image/png", maxEdgePx = 512) { png.asSource() }

        val decoded = assertNotNull(thumbnail?.asUIImage())
        assertEquals(40.0, decoded.size.useContents { width }, "a small image must not be upscaled")
        assertEquals(20.0, decoded.size.useContents { height })
    }

    @Test
    fun an_image_with_transparency_keeps_it() {
        val png = transparentImagePng(width = 200.0, height = 100.0)

        val thumbnail = encoder.encode("image/png", maxEdgePx = 64) { png.asSource() }

        // JPEG has no alpha channel, so compressing this as one would fill every transparent pixel
        // with black — a tile that reads as broken rather than as a small image.
        val cgImage = assertNotNull(thumbnail?.asUIImage()?.CGImage, "a PNG must produce a thumbnail")
        val alpha = CGImageGetAlphaInfo(cgImage)
        assertTrue(
            alpha != CGImageAlphaInfo.kCGImageAlphaNone &&
                alpha != CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst &&
                alpha != CGImageAlphaInfo.kCGImageAlphaNoneSkipLast,
            "transparency must survive the thumbnail, alpha info was $alpha",
        )
    }

    @Test
    fun a_type_that_is_not_an_image_is_declined_without_reading_the_file() {
        var opens = 0

        val thumbnail = encoder.encode("application/pdf", maxEdgePx = 512) {
            opens++
            Buffer()
        }

        assertNull(thumbnail)
        assertEquals(0, opens, "a declined type must not cost a read of the payload")
    }

    @Test
    fun an_image_type_in_upper_case_is_still_decoded() {
        val jpeg = solidImageJpeg(width = 200.0, height = 100.0)

        // A provider reporting `IMAGE/JPEG` must not be mistaken for something undrawable. Asserted
        // by the thumbnail coming back, which is the only proof the gate let it through.
        val thumbnail = encoder.encode("IMAGE/JPEG", maxEdgePx = 64) { jpeg.asSource() }

        assertNotNull(thumbnail?.asUIImage())
    }

    @Test
    fun bytes_that_are_not_an_image_are_declined_rather_than_throwing() {
        val notAnImage = ByteArray(512) { 0x7A }

        // The MIME is a provider's claim, not a fact. ImageIO refuses to open this, and the import
        // must see an ordinary "no thumbnail" rather than an exception.
        val thumbnail = encoder.encode("image/jpeg", maxEdgePx = 64) { notAnImage.asSource() }

        assertNull(thumbnail)
    }

    @Test
    fun an_empty_source_is_declined() {
        val thumbnail = encoder.encode("image/png", maxEdgePx = 64) { Buffer() }

        assertNull(thumbnail)
    }

    // --- fixtures -------------------------------------------------------------------------------

    private fun solidImageJpeg(width: Double, height: Double): ByteArray =
        UIImageJPEGRepresentation(solidImage(width, height), 0.9)!!.toByteArray()

    private fun solidImagePng(width: Double, height: Double): ByteArray =
        UIImagePNGRepresentation(solidImage(width, height))!!.toByteArray()

    /** Half the canvas painted, half left transparent — so the result genuinely carries alpha. */
    private fun transparentImagePng(width: Double, height: Double): ByteArray {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, height), false, 1.0)
        UIColor.orangeColor.setFill()
        platform.UIKit.UIRectFill(CGRectMake(0.0, 0.0, width / 2, height))
        val image = UIGraphicsGetImageFromCurrentImageContext()!!
        UIGraphicsEndImageContext()
        return UIImagePNGRepresentation(image)!!.toByteArray()
    }

    private fun solidImage(width: Double, height: Double): UIImage {
        // scale = 1.0 so the produced pixels are exactly the requested size, whatever the
        // simulator's screen scale happens to be.
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, height), true, 1.0)
        UIColor.orangeColor.setFill()
        platform.UIKit.UIRectFill(CGRectMake(0.0, 0.0, width, height))
        val image = UIGraphicsGetImageFromCurrentImageContext()!!
        UIGraphicsEndImageContext()
        return image
    }

    private fun ByteArray.asSource() = Buffer().apply { write(this@asSource) }

    private fun ByteArray.asUIImage(): UIImage? = UIImage.imageWithData(toNSData())

    private fun ByteArray.toNSData(): NSData =
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    private fun NSData.toByteArray(): ByteArray {
        val out = ByteArray(length.toInt())
        out.usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) }
        return out
    }
}
