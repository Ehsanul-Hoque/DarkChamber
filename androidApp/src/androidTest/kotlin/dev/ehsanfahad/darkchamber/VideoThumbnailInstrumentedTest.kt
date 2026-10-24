package dev.ehsanfahad.darkchamber

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ehsanfahad.darkchamber.app.di.platformModule
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
import kotlinx.io.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication

/**
 * Video previews from the platform's own frame grabber, which only a device has. The clips are made
 * by ffmpeg (`assets/video/`), never by this app, so a bug in our recorder cannot hide in them:
 *
 * - `colour.mp4`: 320×240, black for its first half second and red after, keyframes every half
 *   second, its index (`moov`) at the end as a phone's recorder writes it;
 * - `colour-faststart.mp4`: the same with the index at the start;
 * - `rotated.mp4`: 320×240, red left half and blue right, tagged to be shown turned 90° clockwise;
 * - `short.mp4`: 0.3 s of green; `audio-only.mp4`: no video track.
 *
 * Reached through the platform module's binding, as the vault reaches it. Touches no vault: the
 * clips are read from the test's assets into memory and handed over as streams, the way an import
 * hands over a file. Run on its own with `am instrument -e class …` — the connected Gradle task
 * uninstalls the app afterwards, and with it any vault on the device.
 */
@RunWith(AndroidJUnit4::class)
class VideoThumbnailInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val koin: KoinApplication = koinApplication {
        androidContext(context)
        modules(platformModule())
    }
    private val encoder = koin.koin.get<ThumbnailEncoder>()

    @After
    fun close() = koin.close()

    private fun clip(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("video/$name").use { it.readBytes() }

    private fun preview(name: String, mime: String = "video/mp4"): Bitmap? {
        val bytes = clip(name)
        val encoded = encoder.encode(mime, 512) { Buffer().apply { write(bytes) } } ?: return null
        return BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
    }

    private fun previewOf(name: String): Bitmap = checkNotNull(preview(name)) { "no preview for $name" }

    private fun Bitmap.at(x: Int, y: Int): Int = getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))

    private fun assertColour(expected: String, pixel: Int) {
        val (r, g, b) = Triple(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        val seen = when {
            r > 180 && g < 80 && b < 80 -> "red"
            g > 100 && r < 80 && b < 80 -> "green"
            b > 180 && r < 80 && g < 80 -> "blue"
            r < 40 && g < 40 && b < 40 -> "black"
            else -> "rgb($r,$g,$b)"
        }
        assertEquals(expected, seen)
    }

    /** A second in, so past the black opening; at the clip's own size, which is under the bound. */
    @Test
    fun the_preview_is_a_frame_a_second_in() {
        val frame = previewOf("colour.mp4")
        assertEquals(320 to 240, frame.width to frame.height)
        assertColour("red", frame.at(160, 120))
    }

    @Test
    fun the_index_at_the_start_reads_the_same() {
        val frame = previewOf("colour-faststart.mp4")
        assertColour("red", frame.at(160, 120))
    }

    /** Upright as the clip is shown, not as it was coded: red, the coded left, is now at the top. */
    @Test
    fun a_turned_clip_is_previewed_upright() {
        val frame = previewOf("rotated.mp4")
        assertEquals(240 to 320, frame.width to frame.height)
        assertColour("red", frame.at(120, 40))
        assertColour("blue", frame.at(120, 280))
    }

    @Test
    fun a_clip_shorter_than_a_second_gives_its_first_frame() {
        val frame = previewOf("short.mp4")
        assertColour("green", frame.at(160, 120))
    }

    @Test
    fun a_file_with_no_video_has_no_preview() {
        assertNull(preview("audio-only.mp4"))
    }

    @Test
    fun bytes_that_are_not_a_video_have_no_preview() {
        val bytes = "not a video".encodeToByteArray()
        assertNull(encoder.encode("video/mp4", 512) { Buffer().apply { write(bytes) } })
    }

    /** The MIME is what routes the bytes; an image type is not read as a video. */
    @Test
    fun only_a_video_type_reaches_the_frame_grabber() {
        val bytes = clip("colour.mp4")
        assertNull(encoder.encode("image/jpeg", 512) { Buffer().apply { write(bytes) } })
        assertTrue(encoder.encode("VIDEO/MP4", 512) { Buffer().apply { write(bytes) } } != null)
    }
}
