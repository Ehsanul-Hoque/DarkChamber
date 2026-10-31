package dev.ehsanfahad.darkchamber

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ehsanfahad.darkchamber.app.di.platformModule
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.db.DriverFactory
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import java.io.File

/**
 * Video previews from the platform's own frame grabber, which only a device has. The clips are made
 * by ffmpeg (`assets/video/`), never by this app, so a bug in our recorder cannot hide in them:
 *
 * - `colour.mp4`: 320×240, black for its first half second and red after, keyframes every half
 *   second, its index (`moov`) at the end as a phone's recorder writes it;
 * - `colour-faststart.mp4`: the same with the index at the start;
 * - `sparse-keys.mp4`: the same picture with key frames only at 0 s (black) and 2.4 s (red);
 * - `rotated.mp4`: 320×240, red left half and blue right, tagged to be shown turned 90° clockwise;
 * - `short.mp4`: 0.3 s of green; `audio-only.mp4`: no video track.
 *
 * Reached through the platform module's binding, as the vault reaches it, and read the way an import
 * now reads a video for its preview: each clip is encrypted into a throwaway vault in the test's own
 * cache directory and handed over as a [RangeReader] over that stored object, so the grabber's
 * requests go through real positioned reads and real chunk decrypts. The app's own vault is not
 * touched. Run on its own with `am instrument -e class …` — the connected Gradle task uninstalls the
 * app afterwards, and with it any vault on the device.
 */
@RunWith(AndroidJUnit4::class)
class VideoThumbnailInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val koin: KoinApplication = koinApplication {
        androidContext(context)
        modules(platformModule())
    }
    private val encoder = koin.koin.get<ThumbnailEncoder>()

    // A vault of the test's own: storage under cacheDir, a catalog file beside it, a cheap key derivation.
    private val vaultDir = File(context.cacheDir, "frame-test-${System.nanoTime()}")
    private val engine = CryptographyKotlinEngine()
    private val session = VaultSession.createVault(
        engine,
        DefaultKeyDerivation(engine),
        "frame test".encodeToByteArray(),
        PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1),
    ).session
    private val catalogDriver = DriverFactory(context, File(vaultDir, "catalog.db").also { it.parentFile?.mkdirs() }.absolutePath).create()
    private val objects = VaultObjects(
        session,
        VaultStorage(Path(File(vaultDir, "root").apply { mkdirs() }.absolutePath)),
        (CatalogStore.create(catalogDriver) as Outcome.Ok).value,
        DchmCodec(engine),
        clock = { 0L },
        random = { n -> engine.randomBytes(n) },
    )

    @After
    fun close() {
        koin.close()
        session.close()
        catalogDriver.close()
        vaultDir.deleteRecursively()
        // The SQLite driver keeps a process lock file for each database at cacheDir + the database's
        // absolute path + ".lck"; it outlives the vault, so remove that mirror too, and then any
        // directory it leaves empty (delete() refuses a non-empty one, so nothing else goes).
        var mirror: File? = File(context.cacheDir, vaultDir.absolutePath.trimStart('/'))
        mirror?.deleteRecursively()
        mirror = mirror?.parentFile
        while (mirror != null && mirror != context.cacheDir && mirror.delete()) mirror = mirror.parentFile
    }

    private fun clip(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("video/$name").use { it.readBytes() }

    // Stores [bytes] as a vault object and opens it for reads by position, as import does for a preview.
    private fun stored(bytes: ByteArray): RangeReader {
        val id = objects.importObject(kind = 0, name = "clip", mime = "application/octet-stream", chunkSize = 64 * 1024,
            openSource = { Buffer().apply { write(bytes) } })
        return (objects.openRange((id as Outcome.Ok).value) as Outcome.Ok).value
    }

    private fun preview(name: String, mime: String = "video/mp4"): Bitmap? {
        val reader = stored(clip(name))
        val encoded = try {
            encoder.encode(mime, 512, reader)
        } finally {
            reader.close()
        } ?: return null
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

    /**
     * The first key frame *after* a second, not the nearest one: here the nearest is the black
     * opening frame and the next is a second and a half later, red.
     */
    @Test
    fun the_frame_is_the_key_frame_after_a_second_not_the_nearest() {
        assertColour("red", previewOf("sparse-keys.mp4").at(160, 120))
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
        val reader = stored("not a video".encodeToByteArray())
        assertNull(encoder.encode("video/mp4", 512, reader))
        reader.close()
    }

    /** The MIME is what routes the reader; an image type is not read as a video. */
    @Test
    fun only_a_video_type_reaches_the_frame_grabber() {
        val reader = stored(clip("colour.mp4"))
        assertNull(encoder.encode("image/jpeg", 512, reader))
        assertTrue(encoder.encode("VIDEO/MP4", 512, reader) != null)
        reader.close()
    }

    /** A video handed over as a stream is declined: the grabber needs reads by position, which a stream cannot give. */
    @Test
    fun a_video_handed_over_as_a_stream_is_declined() {
        val bytes = clip("colour.mp4")
        assertNull(encoder.encode("video/mp4", 512) { Buffer().apply { write(bytes) } })
    }

    /**
     * The size is known up front now, so the grabber is told it — and a reader that fails partway
     * (a damaged chunk, a lock) ends in no preview rather than a crash or a frame of something else.
     */
    @Test
    fun a_reader_that_fails_partway_gives_no_preview() {
        val bytes = clip("colour.mp4")
        val failing = object : RangeReader {
            override val length: Long = bytes.size.toLong()
            override fun readAt(position: Long, into: ByteArray, offset: Int, size: Int): Outcome<Int> =
                if (position < 4096) {
                    val n = minOf(size.toLong(), length - position).toInt()
                    bytes.copyInto(into, offset, position.toInt(), position.toInt() + n)
                    Outcome.Ok(n)
                } else {
                    Outcome.Err(VaultError.Crypto.IntegrityFailure)
                }
            override fun close() {}
        }
        assertNull(encoder.encode("video/mp4", 512, failing))
    }
}
