package dev.ehsanfahad.darkchamber.feature.camera.domain

/**
 * Record how far a captured JPEG must be turned to display upright, as its EXIF orientation tag —
 * in memory, leaving the pixels and every other byte of metadata alone.
 *
 * Why here and not in the platform: some camera stacks hand back the sensor's pixels plus a
 * rotation figure and leave the bytes unmarked, and the platform's own EXIF writer only writes to a
 * file or a descriptor — which for a capture would mean plaintext on disk. Every viewer and the
 * thumbnail encoders already honour the tag, so writing it is the whole fix, without re-encoding
 * (which would lose quality and drop the capture time with the rest of the metadata).
 *
 * [rotationDegrees] is clockwise: 0, 90, 180 or 270, mapped to the EXIF 2.3 / TIFF 6.0 values
 * 1, 6, 3 and 8. Anything else returns [jpeg] unchanged.
 *
 * What happens to the bytes, in each case:
 *
 * - **The first IFD has an orientation entry** — its value is rewritten **in place** and [jpeg]
 *   itself is returned. No copy is made, so there is no second plaintext buffer to clean up.
 * - **There is no EXIF block at all** — a minimal one is inserted after the SOI marker (after the
 *   JFIF APP0, when the file starts with one, so it stays a valid JFIF): the orientation, plus the
 *   YCbCrPositioning entry EXIF requires in a JPEG's first IFD. It is the same block exiftool
 *   inserts into such a file.
 *   That needs a longer array, so a **new** array is returned and [jpeg] is left untouched: the
 *   caller then owns two plaintext buffers and must clean up both. Rotation 0 inserts nothing — an
 *   absent tag already means upright.
 * - **There is an EXIF block whose first IFD has no orientation entry** — returned unchanged. Adding
 *   an entry to an existing IFD moves every offset after it, including pointers into other blocks;
 *   getting one wrong corrupts the user's photo, which is a worse outcome than a sideways one.
 * - **Anything malformed or unrecognised** — returned unchanged, for the same reason. Every read is
 *   bounds-checked against the segment it sits in, so a truncated or hostile file cannot make this
 *   read or write outside the array.
 */
internal fun withOrientation(jpeg: ByteArray, rotationDegrees: Int): ByteArray {
    val orientation = orientationFor(rotationDegrees) ?: return jpeg
    if (jpeg.size < 4 || jpeg.u8(0) != 0xFF || jpeg.u8(1) != SOI) return jpeg

    var at = 2
    var insertAt = 2
    while (at + 4 <= jpeg.size) {
        if (jpeg.u8(at) != 0xFF) return jpeg
        val marker = jpeg.u8(at + 1)
        // Start of scan or end of image: the metadata segments are all behind us, and none of them
        // was EXIF. Only now is the file known to be sound enough to insert into.
        if (marker == SOS || marker == EOI) return insertMinimalExif(jpeg, insertAt, orientation)
        val length = jpeg.u16be(at + 2)
        val end = at + 2 + length
        if (length < 2 || end > jpeg.size) return jpeg
        if (marker == APP1 && isExifHeader(jpeg, at + 4, end)) {
            // Only the first EXIF block counts — it is the one every reader uses.
            return rewriteInPlace(jpeg, tiffStart = at + 4 + EXIF_HEADER.size, end = end, orientation)
        }
        // JFIF requires its APP0 to come first, so a block we add goes after it.
        if (at == 2 && marker == APP0 && startsWith(jpeg, at + 4, end, JFIF_HEADER)) insertAt = end
        at = end
    }
    return jpeg
}

private fun insertMinimalExif(jpeg: ByteArray, at: Int, orientation: Int): ByteArray {
    if (orientation == 1) return jpeg // an absent tag already means upright
    val block = byteArrayOf(
        0xFF.toByte(), APP1.toByte(), 0x00, MINIMAL_EXIF_LENGTH.toByte(),
        *EXIF_HEADER,
        // TIFF header: big-endian, 42, first IFD straight after these eight bytes.
        'M'.code.toByte(), 'M'.code.toByte(), 0x00, TIFF_MAGIC.toByte(), 0x00, 0x00, 0x00, 0x08,
        // Two entries, in tag order, each SHORT with one value left-justified in its value field:
        // Orientation, and YCbCrPositioning = 1 (centred) — which EXIF requires in a JPEG's first
        // IFD, and which exiftool writes into the same block for the same reason.
        0x00, 0x02,
        0x01, 0x12, 0x00, TYPE_SHORT.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00,
        0x02, 0x13, 0x00, TYPE_SHORT.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        // No next IFD.
        0x00, 0x00, 0x00, 0x00,
    )
    return jpeg.copyOfRange(0, at) + block + jpeg.copyOfRange(at, jpeg.size)
}

/** EXIF 2.3 §4.6.4 Table 4, Orientation: the clockwise turn needed to view upright. */
private fun orientationFor(rotationDegrees: Int): Int? =
    when (rotationDegrees) {
        0 -> 1
        90 -> 6
        180 -> 3
        270 -> 8
        else -> null
    }

private fun rewriteInPlace(jpeg: ByteArray, tiffStart: Int, end: Int, orientation: Int): ByteArray {
    if (tiffStart + 8 > end) return jpeg
    val bigEndian = when {
        jpeg.u8(tiffStart) == 'M'.code && jpeg.u8(tiffStart + 1) == 'M'.code -> true
        jpeg.u8(tiffStart) == 'I'.code && jpeg.u8(tiffStart + 1) == 'I'.code -> false
        else -> return jpeg
    }
    if (jpeg.u16(tiffStart + 2, bigEndian) != TIFF_MAGIC) return jpeg

    val ifd0 = tiffStart.toLong() + jpeg.u32(tiffStart + 4, bigEndian)
    if (ifd0 + 2 > end) return jpeg
    val entries = jpeg.u16(ifd0.toInt(), bigEndian)
    for (n in 0 until entries) {
        val entry = ifd0.toInt() + 2 + n * IFD_ENTRY_SIZE
        if (entry + IFD_ENTRY_SIZE > end) return jpeg
        if (jpeg.u16(entry, bigEndian) != TAG_ORIENTATION) continue
        // SHORT, one value: the value sits in the first two bytes of the entry's value field.
        val wellFormed = jpeg.u16(entry + 2, bigEndian) == TYPE_SHORT && jpeg.u32(entry + 4, bigEndian) == 1L
        if (!wellFormed) return jpeg
        jpeg.putU16(entry + 8, orientation, bigEndian)
        return jpeg
    }
    return jpeg
}

private fun isExifHeader(jpeg: ByteArray, from: Int, end: Int): Boolean = startsWith(jpeg, from, end, EXIF_HEADER)

private fun startsWith(jpeg: ByteArray, from: Int, end: Int, header: ByteArray): Boolean =
    from + header.size <= end && header.indices.all { jpeg[from + it] == header[it] }

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

private fun ByteArray.u16(at: Int, bigEndian: Boolean): Int =
    if (bigEndian) u16be(at) else (u8(at + 1) shl 8) or u8(at)

private fun ByteArray.u32(at: Int, bigEndian: Boolean): Long {
    val hi = u16(if (bigEndian) at else at + 2, bigEndian).toLong()
    val lo = u16(if (bigEndian) at + 2 else at, bigEndian).toLong()
    return (hi shl 16) or lo
}

private fun ByteArray.putU16(at: Int, value: Int, bigEndian: Boolean) {
    val high = (value shr 8).toByte()
    val low = value.toByte()
    if (bigEndian) {
        this[at] = high
        this[at + 1] = low
    } else {
        this[at] = low
        this[at + 1] = high
    }
}

private const val SOI = 0xD8
private const val SOS = 0xDA
private const val EOI = 0xD9
private const val APP0 = 0xE0
private const val APP1 = 0xE1
private const val TIFF_MAGIC = 42
private const val TAG_ORIENTATION = 0x0112
private const val TYPE_SHORT = 3
private const val IFD_ENTRY_SIZE = 12

/** The inserted APP1's length field: itself (2) + EXIF header (6) + TIFF header (8) + IFD (2 + 2 × 12 + 4). */
private const val MINIMAL_EXIF_LENGTH = 46

/** "Exif" followed by two NULs, which opens every EXIF APP1 payload. */
private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)

/** "JFIF" followed by a NUL, which opens a JFIF APP0 payload. */
private val JFIF_HEADER = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00)
