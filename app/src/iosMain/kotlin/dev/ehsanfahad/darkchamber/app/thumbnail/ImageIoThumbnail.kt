package dev.ehsanfahad.darkchamber.app.thumbnail

import cnames.structs.__CFData
import cnames.structs.__CFDictionary
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGImageRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize

/**
 * Decode the image in [data] with ImageIO, **subsampled** so its longest edge is at most
 * [maxPixelSize] and **upright** — the EXIF orientation applied. `null` when ImageIO will not open
 * it. The caller releases the result with `CGImageRelease`.
 *
 * Shared by the thumbnail encoder and the viewer's decoder, so a photo and its tile are always turned
 * the same way. ImageIO takes the one number and handles the rest: it keeps the aspect ratio and does
 * not upscale, which both callers' tests assert rather than assume.
 *
 * - *Always* from the image: without it ImageIO may hand back a camera's **embedded** thumbnail, whose
 *   size is the camera's choice — typically 160×120. No generated test image carries one, so this is
 *   verified on a device against camera photos.
 * - *With transform*: without it a portrait photo decodes sideways — the pixels are landscape and a
 *   flag says to turn them, which only the full-size renderer honours.
 *
 * Every CoreFoundation object made here is released here; none is ARC-managed from Kotlin.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun imageIoThumbnail(data: NSData, maxPixelSize: Int): CGImageRef? {
    val cfData = CFBridgingRetain(data)
    try {
        val source = CGImageSourceCreateWithData(cfData?.reinterpret<__CFData>(), null) ?: return null
        try {
            val options = thumbnailOptions(maxPixelSize)
            try {
                return CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
            } finally {
                CFBridgingRelease(options) // balances the retain that built the dictionary
            }
        } finally {
            CFRelease(source)
        }
    } finally {
        CFBridgingRelease(cfData)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun thumbnailOptions(maxPixelSize: Int): CFDictionaryRef? =
    CFBridgingRetain(
        mapOf(
            kCGImageSourceCreateThumbnailFromImageAlways.toKey() to true,
            kCGImageSourceThumbnailMaxPixelSize.toKey() to maxPixelSize,
            kCGImageSourceCreateThumbnailWithTransform.toKey() to true,
        ),
    )?.reinterpret<__CFDictionary>()

/**
 * The ImageIO option keys are `CFStringRef` **globals**, which we do not own. `CFBridgingRelease`
 * alone would hand ARC a reference that was never retained for us — an over-release of a constant,
 * the kind of fault that shows up much later and somewhere else. Retaining first makes the pair
 * balance: +1 here, -1 as ARC takes it over.
 */
@OptIn(ExperimentalForeignApi::class)
private fun platform.CoreFoundation.CFStringRef?.toKey(): Any? =
    CFBridgingRelease(CFRetain(this))
