package dev.ehsanfahad.darkchamber.app.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
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
 * A video is handled apart, through the overload that takes a [RangeReader] over the stored object:
 * one frame about a second in, from the platform's frame grabber ([videoFrame]), compressed like a
 * photo's preview. A video handed over as a stream is declined — the grabber needs reads by position.
 *
 * Returning `null` is an ordinary outcome, not an error (see [ThumbnailEncoder]). This encoder
 * declines a MIME that is neither an image nor a video, a file whose bytes are not what its MIME
 * claims, and anything the platform decoder refuses — `BitmapFactory` reports that by returning
 * `null` rather than throwing, so the absence of an exception proves nothing here.
 */
internal class AndroidThumbnailEncoder : ThumbnailEncoder {
    override fun encode(mime: String, maxEdgePx: Int, openSource: () -> RawSource): ByteArray? {
        if (!mime.startsWith(IMAGE_MIME_PREFIX, ignoreCase = true)) return null

        val transform = readTransform(mime, openSource)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openSource().buffered().asInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        // The target is chosen against the picture as it will be *seen*. Feeding it the sensor's own
        // numbers for a quarter-turned photo would fit the wrong edge to the tile.
        val target = if (transform.swapsEdges) {
            thumbnailTargetSize(bounds.outHeight, bounds.outWidth, maxEdgePx)?.swapped()
        } else {
            thumbnailTargetSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
        } ?: return null

        val options = BitmapFactory.Options().apply {
            // Still in the decoded bitmap's own orientation, which is what the decoder subsamples.
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, target)
        }
        val decoded = openSource().buffered().asInputStream().use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        // Scale first, rotate second: the rotation then runs on a bitmap already down at tile size
        // rather than on the subsampled one, and both steps can return their own receiver, so each
        // is compared by identity before anything is recycled — recycling twice would be a
        // use-after-free for the compress below.
        val scaled = decoded.scaledTo(target)
        val upright = scaled.transformedBy(transform)
        return try {
            upright.compressToBytes()
        } finally {
            if (upright !== scaled && upright !== decoded) upright.recycle()
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /** A frame about a second in ([videoFrame]), encoded like a photo's preview. */
    override fun encode(mime: String, maxEdgePx: Int, reader: RangeReader): ByteArray? {
        if (!mime.startsWith(VIDEO_MIME_PREFIX, ignoreCase = true)) return null
        val frame = videoFrame(maxEdgePx, reader) ?: return null
        return try {
            frame.compressToBytes()
        } finally {
            frame.recycle()
        }
    }

    /**
     * The orientation tag, or "nothing to do" — which is the answer for most images and for every
     * failure to read one.
     *
     * A third open of the source, taken only for the formats that carry the tag. `ExifInterface`
     * reads the header and stops, so it is the cheapest of the three reads, but for a file a cloud
     * provider materialises on demand even a header read can be a download — hence the gate.
     *
     * A failure here is never an error: an unreadable tag means the preview is drawn the way the
     * decoder produced it, which is exactly what happened before any of this existed.
     */
    private fun readTransform(mime: String, openSource: () -> RawSource): ThumbnailTransform {
        if (!carriesOrientation(mime)) return UPRIGHT
        val orientation = try {
            openSource().buffered().asInputStream().use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        } catch (_: Exception) {
            return UPRIGHT
        }
        return orientationTransform(orientation)
    }

    /** Applies the rotation and flip the tag asked for; returns the receiver when there is none. */
    private fun Bitmap.transformedBy(transform: ThumbnailTransform): Bitmap {
        if (transform.isIdentity) return this
        val matrix = Matrix().apply {
            // Mirror before rotating, which is the order the tag's own vocabulary is defined in.
            if (transform.mirrored) postScale(-1f, 1f)
            if (transform.degrees != 0) postRotate(transform.degrees.toFloat())
        }
        return try {
            Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        } catch (_: Exception) {
            // Out of memory, or a bitmap the platform declined to copy. A sideways preview beats no
            // preview, and neither is worth failing an import over.
            this
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
        const val VIDEO_MIME_PREFIX = "video/"

        /** What an image with no orientation tag needs doing to it: nothing. */
        val UPRIGHT = ThumbnailTransform(degrees = 0, mirrored = false)

        // 82 is where JPEG artefacts stop being visible at tile size while the file stays small;
        // ignored for PNG, which is lossless.
        const val JPEG_QUALITY = 82
    }
}
