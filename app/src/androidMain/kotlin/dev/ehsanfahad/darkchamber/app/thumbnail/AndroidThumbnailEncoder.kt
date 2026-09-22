package dev.ehsanfahad.darkchamber.app.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
import kotlinx.io.RawSource
import kotlinx.io.asInputStream
import kotlinx.io.buffered
import java.io.ByteArrayOutputStream

/**
 * The Android decoder behind [ThumbnailEncoder]: `BitmapFactory` reads the bounds, then decodes the
 * image **already reduced** and the result is compressed to bytes the vault seals.
 *
 * ### Why the bounds pass is not optional
 *
 * Decoding first and scaling afterwards would allocate the full-resolution bitmap. A 50 MP photo at
 * 4 bytes per pixel is roughly 200 MB in one allocation — comfortably an `OutOfMemoryError` on a
 * mid-range phone, and the import path is required to stay bounded in RAM whatever the user picked.
 * `inJustDecodeBounds` reads the header only (no pixels allocated), which is what makes choosing
 * `inSampleSize` possible; the decoder then never materialises more than roughly
 * `source / inSampleSize` pixels.
 *
 * Two reads of the source, both cheap next to the import's own two passes: the first stops after
 * the header.
 *
 * ### Why it can decline
 *
 * Returning `null` is an ordinary outcome, not an error (see [ThumbnailEncoder]). This encoder
 * declines a MIME that is not an image, a file whose bytes are not the image its MIME claims, and
 * anything the platform decoder refuses — `BitmapFactory` reports that by returning `null` rather
 * than throwing, so the absence of an exception proves nothing here.
 */
internal class AndroidThumbnailEncoder : ThumbnailEncoder {
    override fun encode(mime: String, maxEdgePx: Int, openSource: () -> RawSource): ByteArray? {
        if (!mime.startsWith(IMAGE_MIME_PREFIX, ignoreCase = true)) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openSource().buffered().asInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        val target = thumbnailTargetSize(bounds.outWidth, bounds.outHeight, maxEdgePx) ?: return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, target)
        }
        val decoded = openSource().buffered().asInputStream().use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        // `createScaledBitmap` returns the receiver when nothing needs scaling, so the two can be
        // the same object — recycling it twice would be a use-after-free for the compress above.
        val scaled = decoded.scaledTo(target)
        return try {
            scaled.compressToBytes()
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    // The subsampled bitmap is a power-of-two step above the target, so this is the exact-size step.
    // It runs on an already-small bitmap, so the filtered scale costs little.
    private fun Bitmap.scaledTo(target: ThumbnailSize): Bitmap =
        if (width == target.width && height == target.height) {
            this
        } else {
            Bitmap.createScaledBitmap(this, target.width, target.height, true)
        }

    /**
     * PNG when the image has transparency, JPEG otherwise.
     *
     * JPEG cannot carry an alpha channel, so compressing a transparent PNG or a screenshot as JPEG
     * fills every transparent pixel with black — a tile that looks broken rather than small. JPEG
     * elsewhere because a photographic thumbnail as PNG is several times larger for no visible gain.
     */
    private fun Bitmap.compressToBytes(): ByteArray? {
        val format = if (hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val out = ByteArrayOutputStream()
        val ok = compress(format, JPEG_QUALITY, out)
        return if (ok && out.size() > 0) out.toByteArray() else null
    }

    private companion object {
        const val IMAGE_MIME_PREFIX = "image/"

        // 82 is where JPEG artefacts stop being visible at tile size while the file stays small;
        // ignored for PNG, which is lossless.
        const val JPEG_QUALITY = 82
    }
}
