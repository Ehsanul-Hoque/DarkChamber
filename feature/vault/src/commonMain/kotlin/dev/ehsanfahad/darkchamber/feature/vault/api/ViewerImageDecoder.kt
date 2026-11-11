package dev.ehsanfahad.darkchamber.feature.vault.api

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The platform seam that turns a decrypted image into something the viewer can draw. The app module
 * implements it per platform, as it does the pickers and the camera.
 *
 * **Why not the grid's image loader.** On iOS that loader decodes through Skia, which reads no HEIC
 * and is not known to honour EXIF orientation — and the camera's own photos are HEIC on an iPhone.
 * The thumbnail encoders already decode with each platform's own decoder for exactly this reason,
 * and the viewer needs the same.
 *
 * The contract every implementation keeps:
 *
 * - **Memory only.** [bytes] are decrypted vault content; nothing they become may be written to a
 *   file or a shared cache (hard rule #1).
 * - **Sized at decode.** The result's long edge is at most [targetLongEdge] pixels, subsampled while
 *   decoding rather than scaled after, with the aspect ratio kept.
 * - **Upright.** The EXIF orientation is applied, so a portrait photo is drawn portrait.
 * - **Declines, never throws.** `null` means the platform could not make a picture of these bytes —
 *   a format it does not read, or bytes that are not an image at all.
 * - **Leaves [bytes] alone.** The caller zeroes them once this returns.
 */
interface ViewerImageDecoder {
    fun decode(bytes: ByteArray, targetLongEdge: Int): ImageBitmap?
}
