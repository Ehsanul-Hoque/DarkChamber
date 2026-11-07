package dev.ehsanfahad.darkchamber.app.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How much the viewer subsamples a photo while decoding it. Expected values are worked from the
 * rule — the decoded long edge, rounded **up** as the decoder rounds it, is at most the target —
 * not from the implementation.
 */
class ViewerSamplingTest {
    @Test
    fun a_photo_within_the_target_is_decoded_whole() {
        assertEquals(1, viewerSampleSize(4_000, 3_000, maxLongEdge = 4_800), "12 MP on a 2400 px screen")
        assertEquals(1, viewerSampleSize(4_800, 3_600, maxLongEdge = 4_800), "exactly at the target")
        assertEquals(1, viewerSampleSize(640, 480, maxLongEdge = 4_800), "small images are never enlarged")
    }

    /** 48 MP at full size is about 192 MB of pixels; halving each edge brings it to a quarter. */
    @Test
    fun a_photo_over_the_target_is_halved_until_it_fits() {
        assertEquals(2, viewerSampleSize(8_000, 6_000, maxLongEdge = 4_800), "48 MP landscape")
        assertEquals(2, viewerSampleSize(6_000, 8_000, maxLongEdge = 4_800), "48 MP portrait")
        assertEquals(2, viewerSampleSize(4_801, 100, maxLongEdge = 4_800), "one pixel over")
        assertEquals(16, viewerSampleSize(20_000, 1_000, maxLongEdge = 2_160), "a panorama: 2500 at 8 is still over")
    }

    /** The decoder rounds a subsampled edge up, so 9601 / 2 is 4801 — over a 4800 target. */
    @Test
    fun the_decoded_edge_is_rounded_up_when_checking_the_fit() {
        assertEquals(4, viewerSampleSize(9_601, 7_000, maxLongEdge = 4_800))
        assertEquals(2, viewerSampleSize(9_600, 7_000, maxLongEdge = 4_800))
    }

    /** The platform reports bounds it could not parse as zero or less: there is nothing to decode. */
    @Test
    fun unreadable_bounds_or_no_target_give_no_sample_size() {
        assertNull(viewerSampleSize(0, 3_000, maxLongEdge = 4_800))
        assertNull(viewerSampleSize(4_000, -1, maxLongEdge = 4_800))
        assertNull(viewerSampleSize(4_000, 3_000, maxLongEdge = 0))
    }
}
