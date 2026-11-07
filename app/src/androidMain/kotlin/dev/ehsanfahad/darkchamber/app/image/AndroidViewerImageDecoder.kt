package dev.ehsanfahad.darkchamber.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ehsanfahad.darkchamber.app.thumbnail.readOrientation
import dev.ehsanfahad.darkchamber.app.thumbnail.transformedBy
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerImageDecoder
import java.io.ByteArrayInputStream

/**
 * The Android decoder behind [ViewerImageDecoder]: `BitmapFactory` from the decrypted bytes in
 * memory, subsampled while decoding, then turned upright by the EXIF orientation tag — the same
 * orientation code the thumbnail encoder uses, so a photo and its tile always agree.
 *
 * - **Memory only.** Both reads are of the array; nothing is written to a file or a cache.
 * - **Bounds first.** The header pass allocates no pixels and is what lets [viewerSampleSize] pick a
 *   factor before the decode, so a 48 MP photo is never materialised at full size.
 * - **Declines, never throws.** `BitmapFactory` answers bytes it cannot read with `null`; anything
 *   that does throw — including running out of memory on a large picture — is a decline too, and the
 *   viewer says the file can't be shown rather than closing. HEIC needs Android 9; earlier versions
 *   decline it.
 * - **Leaves [bytes] alone**; the caller zeroes them. The platform decoder's own buffers are its own.
 */
internal class AndroidViewerImageDecoder : ViewerImageDecoder {
    override fun decode(bytes: ByteArray, targetLongEdge: Int): ImageBitmap? =
        try {
            decodeUpright(bytes, targetLongEdge)?.asImageBitmap()
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }

    private fun decodeUpright(bytes: ByteArray, targetLongEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // The long edge is the same before and after a quarter turn, so the stored bounds serve.
        val sample = viewerSampleSize(bounds.outWidth, bounds.outHeight, targetLongEdge) ?: return null

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        val upright = decoded.transformedBy(readOrientation { ByteArrayInputStream(bytes) })
        // The turn is a copy; the sideways one goes at once rather than at the next GC, since two
        // screen-sized bitmaps at a time is the peak this decode has.
        if (upright !== decoded) decoded.recycle()
        return upright
    }
}
