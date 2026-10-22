package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.feature.camera.domain.OrientationFixtures.EXIF_II_1
import dev.ehsanfahad.darkchamber.feature.camera.domain.OrientationFixtures.EXIF_II_6
import dev.ehsanfahad.darkchamber.feature.camera.domain.OrientationFixtures.EXIF_MM_6_INSERTED
import dev.ehsanfahad.darkchamber.feature.camera.domain.OrientationFixtures.EXIF_MM_NO_ORIENTATION
import dev.ehsanfahad.darkchamber.feature.camera.domain.OrientationFixtures.JFIF_EXIF_MM
import dev.ehsanfahad.darkchamber.feature.camera.domain.OrientationFixtures.JFIF_EXIF_MM_3
import dev.ehsanfahad.darkchamber.feature.camera.domain.OrientationFixtures.JFIF_EXIF_MM_6
import dev.ehsanfahad.darkchamber.feature.camera.domain.OrientationFixtures.JFIF_EXIF_MM_8
import dev.ehsanfahad.darkchamber.feature.camera.domain.OrientationFixtures.JFIF_ONLY
import dev.ehsanfahad.darkchamber.feature.camera.domain.OrientationFixtures.NO_METADATA
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Writing a capture's rotation into its JPEG as the EXIF orientation tag, in memory.
 *
 * The expected values come from outside this code: the tag table is the EXIF 2.3 / TIFF 6.0
 * specification's (tag 0x0112; 1 = upright, 3 = rotate 180°, 6 = rotate 90° clockwise to view,
 * 8 = rotate 270° clockwise to view), and the expected bytes are files written by exiftool — see
 * [OrientationFixtures] for how each was made.
 */
class JpegOrientationTest {

    @Test
    fun a_quarter_turn_rewrites_a_big_endian_tag_exactly_as_exiftool_does() {
        assertContentEquals(JFIF_EXIF_MM_6, withOrientation(JFIF_EXIF_MM.copyOf(), rotationDegrees = 90))
    }

    @Test
    fun the_half_and_three_quarter_turns_match_exiftool_too() {
        assertContentEquals(JFIF_EXIF_MM_3, withOrientation(JFIF_EXIF_MM.copyOf(), rotationDegrees = 180))
        assertContentEquals(JFIF_EXIF_MM_8, withOrientation(JFIF_EXIF_MM.copyOf(), rotationDegrees = 270))
    }

    /**
     * Rotation 0 means the pixels are already upright, so a tag saying otherwise would turn the
     * photo a second time on display. It is reset rather than left.
     */
    @Test
    fun no_rotation_resets_a_tag_that_says_otherwise_to_upright() {
        assertContentEquals(JFIF_EXIF_MM, withOrientation(JFIF_EXIF_MM_6.copyOf(), rotationDegrees = 0))
    }

    @Test
    fun a_little_endian_tag_is_rewritten_exactly_as_exiftool_does() {
        assertContentEquals(EXIF_II_6, withOrientation(EXIF_II_1.copyOf(), rotationDegrees = 90))
    }

    /** A rewrite in place makes no copy, so there is no second plaintext buffer to clean up. */
    @Test
    fun a_rewrite_happens_in_the_callers_own_array() {
        val capture = JFIF_EXIF_MM.copyOf()
        assertSame(capture, withOrientation(capture, rotationDegrees = 90))
    }

    /**
     * Adding an entry to an existing IFD would move every offset after it; a mistake there corrupts
     * the user's photo, which is worse than a sideways one. So this case is left alone — and must
     * come back as the very same bytes.
     */
    @Test
    fun an_exif_block_without_an_orientation_entry_is_left_exactly_as_it_was() {
        val capture = EXIF_MM_NO_ORIENTATION.copyOf()
        val result = withOrientation(capture, rotationDegrees = 90)
        assertSame(capture, result)
        assertContentEquals(EXIF_MM_NO_ORIENTATION, result)
    }

    @Test
    fun a_rotation_that_is_not_a_quarter_turn_changes_nothing() {
        for (rotation in listOf(45, -90, 360, 91)) {
            val capture = JFIF_EXIF_MM.copyOf()
            assertContentEquals(JFIF_EXIF_MM, withOrientation(capture, rotation), "rotation $rotation")
        }
    }

    /**
     * Every prefix of a real file must not throw. While the cut lands **inside** the EXIF segment —
     * the SOI, a segment header, the EXIF header, the TIFF header, the IFD, the orientation entry,
     * the rest of the segment — the segment claims more bytes than exist and the file comes back
     * byte-identical, even where the tag itself survived the cut. Once the segment is whole the tag
     * is rewritten, which is right: what follows it is pixel data the rewrite never reads.
     */
    @Test
    fun a_truncated_file_never_throws_and_is_left_alone_until_its_exif_segment_is_whole() {
        val exifEnd = 110 // SOI 2 + APP0 18 + APP1 marker 2 + its declared length 0x58
        for (cut in 0 until JFIF_EXIF_MM.size) {
            val prefix = JFIF_EXIF_MM.copyOf(cut)
            val result = withOrientation(prefix.copyOf(), rotationDegrees = 90)
            val expected = if (cut < exifEnd) prefix else JFIF_EXIF_MM_6.copyOf(cut)
            assertContentEquals(expected, result, "a file cut at $cut bytes")
        }
    }

    /**
     * No EXIF at all: a minimal block is inserted straight after SOI — **exactly the block exiftool
     * inserts** into the same file — and the caller's array is left untouched. The result is a
     * **new** array, so the caller holds two plaintext buffers and must clean up both.
     */
    @Test
    fun a_file_with_no_exif_gains_exactly_the_block_exiftool_would_insert_in_a_new_array() {
        val capture = NO_METADATA.copyOf()
        val result = withOrientation(capture, rotationDegrees = 90)

        assertNotSame(capture, result, "insertion needs a longer array")
        assertContentEquals(NO_METADATA, capture, "the caller's array is untouched")
        assertContentEquals(EXIF_MM_6_INSERTED, result)
    }

    /**
     * A JFIF file must keep its APP0 first, so the block goes after it rather than after SOI — where
     * exiftool puts it too. (exiftool also copies JFIF's resolution into the new block, so its file
     * is not a byte oracle here; the block itself is the one the test above pins.)
     */
    @Test
    fun a_jfif_file_gains_the_block_after_its_app0() {
        val app0End = 20 // SOI 2 + APP0 marker 2 + its declared length 0x10
        // exiftool's block, with its orientation value turned from 6 to 8: marker and length 4,
        // "Exif\0\0" 6, TIFF header 8, entry count 2, then the value's low byte 9 into the entry.
        val block = EXIF_MM_6_INSERTED.copyOfRange(2, 2 + 2 + 0x2E).also { it[4 + 6 + 8 + 2 + 9] = 8 }
        assertContentEquals(
            JFIF_ONLY.copyOfRange(0, app0End) + block + JFIF_ONLY.copyOfRange(app0End, JFIF_ONLY.size),
            withOrientation(JFIF_ONLY.copyOf(), rotationDegrees = 270),
        )
    }

    /** An absent tag already means upright, so there is nothing to insert. */
    @Test
    fun no_rotation_inserts_nothing_into_a_file_with_no_exif() {
        val capture = NO_METADATA.copyOf()
        assertSame(capture, withOrientation(capture, rotationDegrees = 0))
        assertContentEquals(NO_METADATA, capture)
    }

    /** An IFD offset pointing past the segment must not be followed. */
    @Test
    fun an_ifd_offset_outside_the_segment_changes_nothing() {
        val hostile = JFIF_EXIF_MM.copyOf()
        // The IFD0 offset is the four bytes after "MM\0*" — byte 34 onwards in this file.
        hostile[34] = 0x7F
        val expected = hostile.copyOf()
        assertContentEquals(expected, withOrientation(hostile, rotationDegrees = 90))
    }
}
