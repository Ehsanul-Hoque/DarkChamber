package dev.ehsanfahad.darkchamber.app.thumbnail

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Video previews from the simulator's real AVFoundation, over the same ffmpeg-made clips the
 * Android device tests read ([VideoFixtures]):
 *
 * - `colour`: 320×240, black for its first half second and red after, its index at the end;
 * - `colourFaststart`: the same with the index at the start;
 * - `sparseKeys`: the same picture with key frames only at 0 s (black) and 2.4 s (red);
 * - `rotated`: 320×240, red left half and blue right, tagged to be shown turned 90° clockwise;
 * - `short`: 0.3 s of green; `audioOnly`: no video track.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosVideoThumbnailTest {
    private val encoder = IosThumbnailEncoder()

    private fun preview(clip: ByteArray, mime: String = "video/mp4"): CGImageRef? {
        val encoded = encoder.encode(mime, 512) { Buffer().apply { write(clip) } } ?: return null
        val data = encoded.usePinned { NSData.create(bytes = it.addressOf(0), length = encoded.size.toULong()) }
        return UIImage.imageWithData(data)?.CGImage
    }

    /** A second in, so past the black opening; at the clip's own size, which is under the bound. */
    @Test
    fun the_preview_is_a_frame_a_second_in() {
        val frame = assertNotNull(preview(VideoFixtures.colour))
        assertEquals(320 to 240, frame.size())
        assertEquals("red", frame.colourAt(160, 120))
    }

    /**
     * The first key frame *after* a second, not the nearest one: here the nearest is the black
     * opening frame and the next is a second and a half later, red.
     */
    @Test
    fun the_frame_is_the_key_frame_after_a_second_not_the_nearest() {
        val frame = assertNotNull(preview(VideoFixtures.sparseKeys))
        assertEquals("red", frame.colourAt(160, 120))
    }

    @Test
    fun the_index_at_the_start_reads_the_same() {
        val frame = assertNotNull(preview(VideoFixtures.colourFaststart))
        assertEquals("red", frame.colourAt(160, 120))
    }

    /** Upright as the clip is shown, not as it was coded: red, the coded left, is now at the top. */
    @Test
    fun a_turned_clip_is_previewed_upright() {
        val frame = assertNotNull(preview(VideoFixtures.rotated))
        assertEquals(240 to 320, frame.size())
        assertEquals("red", frame.colourAt(120, 40))
        assertEquals("blue", frame.colourAt(120, 280))
    }

    @Test
    fun a_clip_shorter_than_a_second_gives_its_first_frame() {
        val frame = assertNotNull(preview(VideoFixtures.short))
        assertEquals("green", frame.colourAt(160, 120))
    }

    @Test
    fun a_file_with_no_video_has_no_preview() {
        assertNull(preview(VideoFixtures.audioOnly))
    }

    @Test
    fun bytes_that_are_not_a_video_have_no_preview() {
        assertNull(preview("not a video".encodeToByteArray()))
    }

    /**
     * A source that stops mid-read — a lock closing the session under an import's preview, or the
     * file going — gives no preview. The reads run on the resource loader's queue, where an exception
     * would end the process rather than reach the import's own catch.
     */
    @Test
    fun a_source_that_fails_mid_read_has_no_preview() {
        val clip = VideoFixtures.colour
        val failing = encoder.encode("video/mp4", 512) { FailingAfter(clip, failAt = clip.size / 2) }
        assertNull(failing)
        assertNull(encoder.encode("video/mp4", 512) { throw IOException("gone") })
    }

    /** The MIME is what routes the bytes; an image type is not read as a video. */
    @Test
    fun only_a_video_type_reaches_the_frame_grabber() {
        assertNull(preview(VideoFixtures.colour, mime = "image/jpeg"))
        assertNotNull(preview(VideoFixtures.colour, mime = "VIDEO/MP4"))
    }

    // The clip's bytes up to [failAt], then the failure a stopped vault read throws.
    private class FailingAfter(private val bytes: ByteArray, private val failAt: Int) : RawSource {
        private var at = 0

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (at >= failAt) throw IOException("stopped")
            val n = minOf(byteCount, (failAt - at).toLong()).toInt()
            sink.write(bytes, at, at + n)
            at += n
            return n.toLong()
        }

        override fun close() = Unit
    }

    private fun CGImageRef.size(): Pair<Int, Int> = CGImageGetWidth(this).toInt() to CGImageGetHeight(this).toInt()

    // The pixel at (x, y) from the top left, drawn into an RGBA buffer of our own, named roughly.
    private fun CGImageRef.colourAt(x: Int, y: Int): String {
        val (width, height) = size()
        val pixels = ByteArray(width * height * 4)
        pixels.usePinned { pinned ->
            val space = CGColorSpaceCreateDeviceRGB()
            val context = CGBitmapContextCreate(
                pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), this)
            CGContextRelease(context)
            CGColorSpaceRelease(space)
        }
        val i = (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * 4
        val r = pixels[i].toInt() and 0xFF
        val g = pixels[i + 1].toInt() and 0xFF
        val b = pixels[i + 2].toInt() and 0xFF
        return when {
            r > 180 && g < 80 && b < 80 -> "red"
            g > 100 && r < 80 && b < 80 -> "green"
            b > 180 && r < 80 && g < 80 -> "blue"
            r < 40 && g < 40 && b < 40 -> "black"
            else -> "rgb($r,$g,$b)"
        }
    }
}
