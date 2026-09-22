package dev.ehsanfahad.darkchamber.app.thumbnail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sizing arithmetic both platform encoders share, so a thumbnail is the same shape whichever
 * device produced it. Pure by design: the decode itself needs a real platform decoder and only a
 * device can exercise it, but *what size to decode to* is the part that can actually be wrong in an
 * interesting way, and it is reachable from the host.
 */
class ThumbnailSizingTest {
    @Test
    fun a_large_landscape_photo_is_scaled_so_its_longest_edge_is_the_limit() {
        val size = thumbnailTargetSize(sourceWidth = 4000, sourceHeight = 3000, maxEdgePx = 512)

        assertEquals(ThumbnailSize(512, 384), size, "aspect ratio must survive the downscale")
    }

    @Test
    fun a_portrait_photo_scales_by_its_height() {
        val size = thumbnailTargetSize(sourceWidth = 3000, sourceHeight = 4000, maxEdgePx = 512)

        assertEquals(ThumbnailSize(384, 512), size)
    }

    @Test
    fun an_image_smaller_than_the_limit_is_never_upscaled() {
        val size = thumbnailTargetSize(sourceWidth = 100, sourceHeight = 80, maxEdgePx = 512)

        // Blowing a small image up wastes storage and invents detail; the tile scales it instead.
        assertEquals(ThumbnailSize(100, 80), size)
    }

    @Test
    fun an_image_exactly_at_the_limit_is_left_alone() {
        assertEquals(ThumbnailSize(512, 512), thumbnailTargetSize(512, 512, 512))
    }

    @Test
    fun an_extreme_aspect_ratio_keeps_at_least_one_pixel_on_the_short_edge() {
        val size = thumbnailTargetSize(sourceWidth = 10_000, sourceHeight = 3, maxEdgePx = 512)

        // A panorama rounds its short edge to zero, and a zero-dimension bitmap is a crash in the
        // decoder rather than a small thumbnail.
        assertEquals(ThumbnailSize(512, 1), size)
    }

    @Test
    fun bounds_the_decoder_could_not_read_produce_no_size() {
        // BitmapFactory reports 0 (or -1) for a file it cannot parse, and ImageIO omits the keys
        // entirely. Both land here, and both mean "there is no thumbnail to make".
        assertNull(thumbnailTargetSize(0, 0, 512))
        assertNull(thumbnailTargetSize(-1, 100, 512))
        assertNull(thumbnailTargetSize(100, 0, 512))
    }

    @Test
    fun a_non_positive_limit_produces_no_size() {
        assertNull(thumbnailTargetSize(4000, 3000, 0))
    }

    @Test
    fun the_sample_size_halves_as_far_as_it_can_without_going_under_the_target() {
        val target = ThumbnailSize(512, 384)

        // 4000x3000 at 1/4 is 1000x750 — still above target. 1/8 would be 500x375, under it on both
        // edges, so the decoder would hand back something that has to be scaled *up*.
        assertEquals(4, sampleSizeFor(4000, 3000, target))
    }

    @Test
    fun the_sample_size_respects_whichever_edge_runs_out_first() {
        // A target that is **not** the source's aspect ratio, which is the only case where the two
        // edges disagree — an aspect-matched target has both run out at the same step, so a test
        // using one would pass whether the check considered both edges or either.
        //
        // 4000x600 against 250x100: the width would tolerate 1/16, the height stops it at 1/4.
        // Going further would hand back a bitmap shorter than the target, which then has to be
        // scaled *up* — memory spent to produce a blurrier tile.
        val target = ThumbnailSize(250, 100)

        assertEquals(4, sampleSizeFor(4000, 600, target))
    }

    @Test
    fun a_source_already_at_or_below_the_target_is_not_subsampled() {
        assertEquals(1, sampleSizeFor(400, 300, ThumbnailSize(400, 300)))
        assertEquals(1, sampleSizeFor(100, 80, ThumbnailSize(100, 80)))
    }

    @Test
    fun every_sample_size_is_a_power_of_two() {
        // BitmapFactory rounds a non-power-of-two down to one anyway, so a value that is not one is
        // a silent lie about the dimensions that will come back.
        for (width in listOf(100, 513, 1024, 4000, 12_000, 50_000)) {
            val target = thumbnailTargetSize(width, width, 512) ?: continue
            val sample = sampleSizeFor(width, width, target)
            assertTrue(sample >= 1, "sample size must be at least 1, was $sample for $width")
            assertTrue(sample and (sample - 1) == 0, "sample size must be a power of two, was $sample")
        }
    }
}
