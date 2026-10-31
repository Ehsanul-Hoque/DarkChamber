package dev.ehsanfahad.darkchamber.app.thumbnail

import dev.ehsanfahad.darkchamber.app.testSqlDriver
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Video previews from the simulator's real AVFoundation, over the same ffmpeg-made clips the
 * Android device tests read ([VideoFixtures]):
 *
 * - `colour`: 320×240, black for its first half second and red after, its index at the end;
 * - `colourFaststart`: the same with the index at the start;
 * - `sparseKeys`: the same picture with key frames only at 0 s (black) and 2.4 s (red);
 * - `rotated`: 320×240, red left half and blue right, tagged to be shown turned 90° clockwise;
 * - `short`: 0.3 s of green; `audioOnly`: no video track.
 *
 * Each clip is read the way an import now reads a video for its preview: encrypted into a throwaway
 * vault on a temp root and handed over as a [RangeReader] over that stored object, so every request
 * the resource loader answers is a real positioned read and chunk decrypt.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosVideoThumbnailTest {
    private val encoder = IosThumbnailEncoder()

    private val root = Path(SystemTemporaryDirectory, "ivt-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val session = VaultSession.createVault(
        engine, DefaultKeyDerivation(engine), "frame test".encodeToByteArray(),
        PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1),
    ).session
    private val objects = run {
        SystemFileSystem.createDirectories(root)
        VaultObjects(session, VaultStorage(root), (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value,
            DchmCodec(engine), clock = { 0L }, random = { n -> engine.randomBytes(n) })
    }

    @AfterTest
    fun cleanup() {
        session.close()
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    // Stores [bytes] as a vault object (64 KiB chunks, so even a small clip spans several) and opens it by position.
    private fun stored(bytes: ByteArray): RangeReader {
        val id = objects.importObject(kind = 0, name = "clip", mime = "application/octet-stream", chunkSize = 64 * 1024,
            openSource = { Buffer().apply { write(bytes) } })
        return (objects.openRange((id as Outcome.Ok).value) as Outcome.Ok).value
    }

    private fun preview(clip: ByteArray, mime: String = "video/mp4"): CGImageRef? = previewThrough(stored(clip), mime)

    private fun previewThrough(reader: RangeReader, mime: String = "video/mp4"): CGImageRef? {
        val encoded = try {
            encoder.encode(mime, 512, reader)
        } finally {
            reader.close()
        } ?: return null
        val data = encoded.usePinned { NSData.create(bytes = it.addressOf(0), length = encoded.size.toULong()) }
        return UIImage.imageWithData(data)?.CGImage
    }

    /** A second in, so past the black opening; at the clip's own size, which is under the bound. */
    @Test
    fun the_preview_is_a_frame_a_second_in() {
        val frame = assertNotNull(preview(VideoFixtures.colour))
        assertEquals(320 to 240, frame.size())
        assertEquals("red", frame.colourAt(160, 120))
    }

    /**
     * The first key frame *after* a second, not the nearest one: here the nearest is the black
     * opening frame and the next is a second and a half later, red.
     */
    @Test
    fun the_frame_is_the_key_frame_after_a_second_not_the_nearest() {
        val frame = assertNotNull(preview(VideoFixtures.sparseKeys))
        assertEquals("red", frame.colourAt(160, 120))
    }

    @Test
    fun the_index_at_the_start_reads_the_same() {
        val frame = assertNotNull(preview(VideoFixtures.colourFaststart))
        assertEquals("red", frame.colourAt(160, 120))
    }

    /** Upright as the clip is shown, not as it was coded: red, the coded left, is now at the top. */
    @Test
    fun a_turned_clip_is_previewed_upright() {
        val frame = assertNotNull(preview(VideoFixtures.rotated))
        assertEquals(240 to 320, frame.size())
        assertEquals("red", frame.colourAt(120, 40))
        assertEquals("blue", frame.colourAt(120, 280))
    }

    @Test
    fun a_clip_shorter_than_a_second_gives_its_first_frame() {
        val frame = assertNotNull(preview(VideoFixtures.short))
        assertEquals("green", frame.colourAt(160, 120))
    }

    @Test
    fun a_file_with_no_video_has_no_preview() {
        assertNull(preview(VideoFixtures.audioOnly))
    }

    @Test
    fun bytes_that_are_not_a_video_have_no_preview() {
        assertNull(preview("not a video".encodeToByteArray()))
    }

    /**
     * A reader that fails partway — a chunk that fails its tag, a lock under an import's preview —
     * gives no preview, and no crash: the reads run on the resource loader's queue, where an
     * exception would end the process rather than reach the import's own catch.
     */
    @Test
    fun a_reader_that_fails_on_bytes_the_frame_needs_gives_no_preview() {
        // Index at the end: the frame needs the index, so a failure anywhere before the last few bytes stops it.
        for (failFrom in listOf(0L, 1024L, 2048L, 3072L, 4096L)) {
            assertNull(previewThrough(ScriptedReader(VideoFixtures.colour, failFrom)), "index at the end, failing from $failFrom")
        }
        // Index at the start: the second's frame lies in the first 3 KB.
        for (failFrom in listOf(0L, 1024L, 2048L)) {
            assertNull(previewThrough(ScriptedReader(VideoFixtures.colourFaststart, failFrom)), "index at the start, failing from $failFrom")
        }
    }

    /**
     * A failure in bytes the frame never needs does not stop the frame — that is the reader reading
     * only what it is asked for. Damage there is not the encoder's to judge: the vault watches every
     * read it hands out and fails the import on any of them.
     */
    @Test
    fun a_reader_that_fails_only_past_what_the_frame_needs_still_gives_the_frame() {
        val clip = VideoFixtures.colourFaststart
        val frame = assertNotNull(previewThrough(ScriptedReader(clip, failFrom = 4096L)))
        assertEquals("red", frame.colourAt(160, 120))
    }

    /** A video handed over as a stream is declined: the grabber needs reads by position. */
    @Test
    fun a_video_handed_over_as_a_stream_is_declined() {
        val clip = VideoFixtures.colour
        assertNull(encoder.encode("video/mp4", 512) { Buffer().apply { write(clip) } })
    }

    /**
     * AVFoundation asks for the length first. The reader knows it, so the loader must not learn it by
     * reading the file through — on a phone's clip, index at the end, that would be a pass over the
     * whole file before the first frame. Here the clip (index at the start) is followed by a megabyte
     * that fails to read: a loader that reads through to find the end hits it and the preview fails.
     */
    @Test
    fun the_length_comes_from_the_reader_not_from_reading_the_file_through() {
        val clip = VideoFixtures.colourFaststart
        val padded = clip + ByteArray(1024 * 1024)
        val frame = assertNotNull(previewThrough(ScriptedReader(padded, failFrom = clip.size.toLong())))
        assertEquals("red", frame.colourAt(160, 120))
    }

    /** The MIME is what routes the reader; an image type is not read as a video. */
    @Test
    fun only_a_video_type_reaches_the_frame_grabber() {
        assertNull(preview(VideoFixtures.colour, mime = "image/jpeg"))
        assertNotNull(preview(VideoFixtures.colour, mime = "VIDEO/MP4"))
    }

    // [bytes] by position, with every read at or after [failFrom] failing as a damaged chunk would.
    private class ScriptedReader(private val bytes: ByteArray, private val failFrom: Long) : RangeReader {
        override val length: Long = bytes.size.toLong()

        override fun readAt(position: Long, into: ByteArray, offset: Int, size: Int): Outcome<Int> {
            if (position >= length) return Outcome.Ok(RangeReader.END)
            if (position >= failFrom) return Outcome.Err(VaultError.Crypto.IntegrityFailure)
            val n = minOf(size.toLong(), failFrom - position, length - position).toInt()
            bytes.copyInto(into, offset, position.toInt(), position.toInt() + n)
            return Outcome.Ok(n)
        }

        override fun close() = Unit
    }

    private fun CGImageRef.size(): Pair<Int, Int> = CGImageGetWidth(this).toInt() to CGImageGetHeight(this).toInt()

    // The pixel at (x, y) from the top left, drawn into an RGBA buffer of our own, named roughly.
    private fun CGImageRef.colourAt(x: Int, y: Int): String {
        val (width, height) = size()
        val pixels = ByteArray(width * height * 4)
        pixels.usePinned { pinned ->
            val space = CGColorSpaceCreateDeviceRGB()
            val context = CGBitmapContextCreate(
                pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), this)
            CGContextRelease(context)
            CGColorSpaceRelease(space)
        }
        val i = (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * 4
        val r = pixels[i].toInt() and 0xFF
        val g = pixels[i + 1].toInt() and 0xFF
        val b = pixels[i + 2].toInt() and 0xFF
        return when {
            r > 180 && g < 80 && b < 80 -> "red"
            g > 100 && r < 80 && b < 80 -> "green"
            b > 180 && r < 80 && g < 80 -> "blue"
            r < 40 && g < 40 && b < 40 -> "black"
            else -> "rgb($r,$g,$b)"
        }
    }
}
