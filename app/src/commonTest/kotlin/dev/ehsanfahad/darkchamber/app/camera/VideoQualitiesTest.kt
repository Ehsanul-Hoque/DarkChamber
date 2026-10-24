package dev.ehsanfahad.darkchamber.app.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoQualitiesTest {
    /**
     * What a lens is offered at, from whether it records 1080p and 2160p. Each is offered on its
     * own; a lens with neither offers no video quality at all.
     */
    @Test
    fun the_lens_qualities_become_the_offered_ones() {
        assertEquals(setOf(VideoQuality.HD, VideoQuality.UHD), videoQualitiesFor(fullHd = true, ultraHd = true))
        assertEquals(setOf(VideoQuality.HD), videoQualitiesFor(fullHd = true, ultraHd = false))
        assertEquals(setOf(VideoQuality.UHD), videoQualitiesFor(fullHd = false, ultraHd = true))
        assertEquals(emptySet<VideoQuality>(), videoQualitiesFor(fullHd = false, ultraHd = false))
    }
}
