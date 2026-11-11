package dev.ehsanfahad.darkchamber.app.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.ehsanfahad.darkchamber.app.thumbnail.imageIoThumbnail
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerImageDecoder
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSData
import platform.Foundation.dataWithBytesNoCopy

/**
 * The iOS decoder behind [ViewerImageDecoder]: ImageIO, through the same call the thumbnail encoder
 * uses ([imageIoThumbnail]) — subsampled to the target while decoding, the EXIF orientation applied,
 * HEIC included. The grid's image loader decodes with Skia, which reads no HEIC; the camera's own
 * photos are HEIC on an iPhone.
 *
 * **No copy of the plaintext is made on the way in.** The `NSData` handed to ImageIO wraps the
 * caller's array without copying it (`dataWithBytesNoCopy`, not freed by Foundation), held pinned
 * until ImageIO is done — so the caller's zeroing covers every encoded byte this code put anywhere.
 * ImageIO's own buffers are its own, and are not claimed.
 *
 * **One decoded copy is made on the way out**: ImageIO's picture is drawn straight into the Skia
 * bitmap's memory rather than through an intermediate array, then ImageIO's is released.
 *
 * Declines, never throws: bytes ImageIO will not open, an empty array, or anything that throws.
 */
internal class IosViewerImageDecoder : ViewerImageDecoder {
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override fun decode(bytes: ByteArray, targetLongEdge: Int): ImageBitmap? {
        if (bytes.isEmpty() || targetLongEdge <= 0) return null
        return try {
            autoreleasepool {
                bytes.usePinned { pinned ->
                    val data = NSData.dataWithBytesNoCopy(
                        bytes = pinned.addressOf(0),
                        length = bytes.size.toULong(),
                        freeWhenDone = false,
                    )
                    val image = imageIoThumbnail(data, targetLongEdge) ?: return@usePinned null
                    try {
                        image.toImageBitmap()
                    } finally {
                        CGImageRelease(image)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Draws [this] into a Skia bitmap's own pixels: premultiplied RGBA, top row first. */
    @OptIn(ExperimentalForeignApi::class)
    private fun CGImageRef.toImageBitmap(): ImageBitmap? {
        val width = CGImageGetWidth(this).toInt()
        val height = CGImageGetHeight(this).toInt()
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap()
        if (!bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))) {
            bitmap.close()
            return null
        }
        val pixels = bitmap.peekPixels() ?: run {
            bitmap.close()
            return null
        }
        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            data = interpretCPointer<ByteVar>(pixels.addr),
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = pixels.rowBytes.toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
        )
        CGColorSpaceRelease(colorSpace)
        if (context == null) {
            bitmap.close()
            return null
        }
        try {
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), this)
        } finally {
            CGContextRelease(context)
        }
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }
}
