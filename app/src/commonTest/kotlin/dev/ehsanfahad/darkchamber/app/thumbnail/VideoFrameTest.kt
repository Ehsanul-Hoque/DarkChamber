package dev.ehsanfahad.darkchamber.app.thumbnail

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which frame of a video becomes its preview. */
class VideoFrameTest {
    /**
     * About a second in, past the black a camera's first frames often are; the first frame of a
     * clip no longer than that, and of one whose length the file does not say.
     */
    @Test
    fun the_frame_is_a_second_in_unless_the_clip_is_shorter() {
        assertEquals(1_000_000L, previewFrameTimeUs(durationMs = 90_000))
        assertEquals(1_000_000L, previewFrameTimeUs(durationMs = 1_001))
        assertEquals(0L, previewFrameTimeUs(durationMs = 1_000), "a 1 s clip has no frame a second in")
        assertEquals(0L, previewFrameTimeUs(durationMs = 300))
        assertEquals(0L, previewFrameTimeUs(durationMs = null))
    }
}
