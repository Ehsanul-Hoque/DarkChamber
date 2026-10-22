package dev.ehsanfahad.darkchamber.feature.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureResult
import dev.ehsanfahad.darkchamber.feature.camera.api.CapturedPhoto
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CameraTypesTest {

    /**
     * A captured photo is plaintext image content, and anything that prints one — a log line, a
     * failed assertion, a crash report — must print its size and nothing of its bytes (hard rule
     * #4). The fixture's bytes are distinctive in both decimal and hex so either spelling would show.
     */
    @Test
    fun a_captured_photo_prints_its_size_and_none_of_its_bytes() {
        val photo = CapturedPhoto(byteArrayOf(0x5A, 0x6B, 0x7C, 0x4D), rotationDegrees = 90)
        val printed = listOf(photo.toString(), CaptureResult.Captured(photo).toString())

        for (text in printed) {
            assertTrue("4B" in text, "the size is reported: $text")
            for (leak in listOf("90, 107", "5A", "5a", "6B", "6b", "[")) {
                assertFalse(leak in text, "no byte of the photo may appear in \"$text\"")
            }
        }
    }

    /**
     * Capabilities travel in a `StateFlow`, which drops a value equal to the last. Flipping to a lens
     * that differs only in the video it records must still arrive, or the quality chip would offer
     * 4K on a lens that cannot record it.
     */
    @Test
    fun capabilities_that_differ_only_in_video_quality_are_not_equal() {
        val hd = CameraCapabilities(setOf(Lens.Back, Lens.Front), true, 1f, 8f, setOf(VideoQuality.HD))
        val uhd = CameraCapabilities(setOf(Lens.Back, Lens.Front), true, 1f, 8f, setOf(VideoQuality.HD, VideoQuality.UHD))

        assertNotEquals(hd, uhd)
        assertEquals(hd, CameraCapabilities(setOf(Lens.Back, Lens.Front), true, 1f, 8f, setOf(VideoQuality.HD)))
    }

    /** The queue tests depend on this: a held capture waits for the test, oldest first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun the_fake_holds_captures_until_released_in_order() = runTest {
        val camera = FakeCameraController()
        camera.holdCaptures()

        val first = async { camera.capture() }
        val second = async { camera.capture() }
        testScheduler.advanceUntilIdle()
        assertEquals(2, camera.heldCount, "both captures are waiting")
        assertFalse(first.isCompleted)

        camera.release(CaptureResult.Captured(CapturedPhoto(byteArrayOf(1), 0)))
        camera.release(CaptureResult.Failed)

        val one = assertIs<CaptureResult.Captured>(first.await())
        assertContentEquals(byteArrayOf(1), one.photo.jpeg, "the oldest capture gets the first answer")
        assertEquals(CaptureResult.Failed, second.await())
    }

    @Test
    fun the_fakes_default_captures_never_share_an_array() = runTest {
        val camera = FakeCameraController()
        val a = assertIs<CaptureResult.Captured>(camera.capture()).photo.jpeg
        val b = assertIs<CaptureResult.Captured>(camera.capture()).photo.jpeg

        a.fill(0)
        assertFalse(b.all { it == 0.toByte() }, "zeroing one capture must leave the next untouched")
    }
}
