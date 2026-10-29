package dev.ehsanfahad.darkchamber.app.thumbnail

import cnames.structs.__CFData
import cnames.structs.__CFDictionary
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

/**
 * The iOS decoder behind [ThumbnailEncoder]: `CGImageSourceCreateThumbnailAtIndex` decodes
 * **subsampled** to `kCGImageSourceThumbnailMaxPixelSize` — the platform's own equivalent of
 * Android's `inSampleSize`, and the reason a 50 MP photo never materialises as a full bitmap.
 *
 * ### Why this does not use the shared sizing the way Android does
 *
 * The Android encoder has to compute a target, because `BitmapFactory` decides nothing for itself.
 * ImageIO takes **one** number — the longest edge — and handles the rest: it preserves the aspect
 * ratio, and it does not upscale, so a 40×20 image asked for a 512 limit comes back 40×20. Both of
 * those are asserted by this encoder's tests rather than assumed from the documentation.
 *
 * So the limit is passed straight through. An earlier version read the image's declared dimensions
 * first and ran them through the shared sizing; every mutation of that arithmetic survived, because
 * ImageIO was independently producing the same answer. What it did carry was ~25 lines of
 * hand-balanced CoreFoundation retain/release — the kind that fails late and somewhere else — in
 * exchange for nothing observable.
 *
 * `…WithTransform` applies the EXIF orientation to the thumbnail. Without it, a photo taken in
 * portrait decodes sideways: the pixels are landscape and a flag says to rotate them, and only the
 * full-size renderer honours that flag. The stored original keeps its EXIF untouched (nothing is
 * stripped on import) — this rotates the *preview* so the tile matches what the viewer will show.
 *
 * A video is handled apart, through the overload that takes a [RangeReader] over the stored object:
 * one frame about a second in, from `AVAssetImageGenerator` reading through a resource loader
 * ([videoFrame]), compressed like a photo's preview. A video handed over as a stream is declined.
 *
 * ### The memory it does use
 *
 * The encoded file is read into memory whole, because `CGImageSourceCreateWithData` wants the bytes
 * and the seam hands over a stream, not a path. That is the **compressed** size — a few MB for a
 * phone photo — not the decoded size, which is the allocation that actually matters and which
 * subsampling is what avoids. Taking a path instead would mean widening the seam with something
 * only some sources can supply: an Android `content://` URI has no path, and the vault's own
 * spilled copy would have to expose one.
 */
internal class IosThumbnailEncoder : ThumbnailEncoder {
    @OptIn(ExperimentalForeignApi::class)
    override fun encode(mime: String, maxEdgePx: Int, openSource: () -> RawSource): ByteArray? {
        if (!mime.startsWith(IMAGE_MIME_PREFIX, ignoreCase = true)) return null

        val bytes = openSource().buffered().use { it.readByteArray() }
        if (bytes.isEmpty()) return null

        // CFBridgingRetain hands the NSData to CoreFoundation; the matching release is below. An
        // image source over it must be released too — neither is ARC-managed from Kotlin.
        val data = CFBridgingRetain(bytes.toNSData())
        val source = CGImageSourceCreateWithData(data?.reinterpret<__CFData>(), null)
        if (source == null) {
            CFBridgingRelease(data)
            return null // ImageIO would not open it: not the image the MIME claimed
        }

        return try {
            val options = thumbnailOptions(maxEdgePx)
            val thumbnail = try {
                CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
            } finally {
                CFBridgingRelease(options) // balances the retain that built the dictionary
            } ?: return null
            try {
                thumbnail.encodeToBytes()
            } finally {
                CGImageRelease(thumbnail)
            }
        } finally {
            CFRelease(source)
            CFBridgingRelease(data)
        }
    }

    /** A frame about a second in ([videoFrame]), encoded like a photo's preview. */
    @OptIn(ExperimentalForeignApi::class)
    override fun encode(mime: String, maxEdgePx: Int, reader: RangeReader): ByteArray? {
        if (!mime.startsWith(VIDEO_MIME_PREFIX, ignoreCase = true)) return null
        val frame = videoFrame(mime, maxEdgePx, reader) ?: return null
        return try {
            frame.encodeToBytes()
        } finally {
            CGImageRelease(frame)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun thumbnailOptions(maxPixelSize: Int): CFDictionaryRef? =
        CFBridgingRetain(
            mapOf(
                // Always: without it ImageIO is free to hand back a camera's *embedded* thumbnail,
                // whose size and quality are the camera's choice — typically 160x120, which is a
                // blurry tile. No test here reaches it: a generated image carries no embedded
                // thumbnail, so ImageIO renders from the full image either way, and removing this
                // line leaves every test green. It is verified on device against camera photos.
                kCGImageSourceCreateThumbnailFromImageAlways.toKey() to true,
                kCGImageSourceThumbnailMaxPixelSize.toKey() to maxPixelSize,
                kCGImageSourceCreateThumbnailWithTransform.toKey() to true,
            ),
        )?.reinterpret<__CFDictionary>()

    /**
     * PNG when the image carries transparency, JPEG otherwise — the same rule as Android, for the
     * same reason: JPEG has no alpha channel, so a screenshot compressed as one comes back with
     * black behind everything that was transparent.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun CGImageRef.encodeToBytes(): ByteArray? {
        val image = UIImage.imageWithCGImage(this)
        val data = if (hasAlpha()) {
            UIImagePNGRepresentation(image)
        } else {
            UIImageJPEGRepresentation(image, JPEG_QUALITY)
        }
        return data?.takeIf { it.length > 0u }?.toByteArray()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun CGImageRef.hasAlpha(): Boolean =
        when (CGImageGetAlphaInfo(this)) {
            CGImageAlphaInfo.kCGImageAlphaNone,
            CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst,
            CGImageAlphaInfo.kCGImageAlphaNoneSkipLast,
            -> false

            else -> true
        }

    private companion object {
        const val IMAGE_MIME_PREFIX = "image/"
        const val VIDEO_MIME_PREFIX = "video/"

        // Matches the Android encoder's 82, so the two platforms produce comparable tiles.
        const val JPEG_QUALITY = 0.82
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/**
 * The ImageIO option and property keys are `CFStringRef` **globals**, which we do not own.
 * `CFBridgingRelease` alone would hand ARC a reference that was never retained for us — an
 * over-release of a constant, which is the kind of fault that shows up much later and somewhere
 * else. Retaining first makes the pair balance: +1 here, -1 as ARC takes it over.
 */
@OptIn(ExperimentalForeignApi::class)
private fun platform.CoreFoundation.CFStringRef?.toKey(): Any? =
    CFBridgingRelease(CFRetain(this))
