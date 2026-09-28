package dev.ehsanfahad.darkchamber.app.thumbnail

import androidx.exifinterface.media.ExifInterface

/**
 * What has to be done to a decoded bitmap for it to look like the photo the user took.
 *
 * [degrees] is clockwise; [mirrored] means flip horizontally *before* rotating. [swapsEdges] says
 * the picture's width and height are the decoded bitmap's the other way round, which the sizing
 * needs before it can pick a target.
 */
internal data class ThumbnailTransform(
    val degrees: Int,
    val mirrored: Boolean,
) {
    /** True for the quarter turns: a 4000×3000 frame tagged ROTATE_90 is a 3000×4000 picture. */
    val swapsEdges: Boolean get() = degrees == 90 || degrees == 270

    /** Nothing to do — the overwhelmingly common case, and the answer for anything unrecognised. */
    val isIdentity: Boolean get() = degrees == 0 && !mirrored
}

/**
 * Read one EXIF orientation value into the transform it stands for.
 *
 * The eight values are the whole of the tag's vocabulary; anything else — a missing tag, a zero, a
 * number no camera should write — is treated as upright, because rotating on a guess is worse than
 * not rotating at all. Most images reaching this have no tag: every PNG, every screenshot, anything
 * a metadata scrubber has already been through.
 */
internal fun orientationTransform(orientation: Int): ThumbnailTransform = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> ThumbnailTransform(90, mirrored = false)
    ExifInterface.ORIENTATION_ROTATE_180 -> ThumbnailTransform(180, mirrored = false)
    ExifInterface.ORIENTATION_ROTATE_270 -> ThumbnailTransform(270, mirrored = false)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ThumbnailTransform(0, mirrored = true)
    // A vertical flip is a horizontal one turned through half a circle; expressing it that way
    // means the matrix below only ever needs one kind of flip.
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ThumbnailTransform(180, mirrored = true)
    // Transpose is a mirror plus **three** quarter turns and transverse is a mirror plus one — not
    // the other way round, which is what this said until a device run drew both of them upside
    // down. The pairing is easy to get backwards because the names suggest the diagonals rather
    // than the rotations, and a unit test written from the same misreading agreed with it.
    ExifInterface.ORIENTATION_TRANSPOSE -> ThumbnailTransform(270, mirrored = true)
    ExifInterface.ORIENTATION_TRANSVERSE -> ThumbnailTransform(90, mirrored = true)
    else -> ThumbnailTransform(0, mirrored = false)
}

/**
 * Whether this MIME is one whose files carry an orientation tag worth opening the source for.
 *
 * Reading the tag costs an extra read of the picked file, and for a document a cloud provider
 * materialises on demand that is not free. JPEG and HEIC carry one; PNG, WebP and GIF as this app
 * encounters them do not, so asking would be a round-trip spent to learn nothing.
 */
internal fun carriesOrientation(mime: String): Boolean =
    ORIENTED_MIMES.any { mime.equals(it, ignoreCase = true) }

private val ORIENTED_MIMES = listOf("image/jpeg", "image/jpg", "image/heic", "image/heif")
