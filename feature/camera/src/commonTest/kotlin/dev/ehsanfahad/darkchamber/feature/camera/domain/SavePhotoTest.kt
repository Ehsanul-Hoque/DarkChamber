package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.camera.FakeCaptureVault
import dev.ehsanfahad.darkchamber.feature.camera.api.CapturedPhoto
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SavePhotoTest {

    private val vault = FakeCaptureVault()
    private val save = SavePhoto(vault, clock = { 1_000L }, nameFor = { "Photo at $it.jpg" })

    @Test
    fun a_capture_is_imported_as_a_jpeg_file_into_the_destination_album() {
        val album = byteArrayOf(7, 7)
        val result = save(CapturedPhoto(OrientationFixtures.JFIF_EXIF_MM.copyOf(), rotationDegrees = 0), album.copyOf()) { true }

        assertIs<Outcome.Ok<ByteArray>>(result)
        val stored = vault.imported.single()
        assertEquals(VaultItemKind.FILE.code, stored.kind)
        assertEquals("image/jpeg", stored.mime)
        assertEquals("Photo at 1000.jpg", stored.name, "named from the clock")
        assertContentEquals(album, stored.albumId, "filed where the camera was opened")
        assertContentEquals(OrientationFixtures.JFIF_EXIF_MM, stored.bytes, "the vault received the JPEG")
    }

    @Test
    fun a_capture_from_the_whole_vault_or_unfiled_is_left_unfiled() {
        save(CapturedPhoto(OrientationFixtures.JFIF_EXIF_MM.copyOf(), 0), albumId = null) { true }
        assertEquals(null, vault.imported.single().albumId)
    }

    /** The rotation is written into the bytes the vault receives — not left for a viewer to guess. */
    @Test
    fun the_vault_receives_the_jpeg_with_its_orientation_written_in() {
        save(CapturedPhoto(OrientationFixtures.JFIF_EXIF_MM.copyOf(), rotationDegrees = 90), null) { true }
        assertContentEquals(OrientationFixtures.JFIF_EXIF_MM_6, vault.imported.single().bytes)
    }

    // Only our arrays are claimed to be cleared; the names say so.

    @Test
    fun our_array_is_zeroed_after_a_saved_photo() {
        val jpeg = OrientationFixtures.JFIF_EXIF_MM.copyOf()
        save(CapturedPhoto(jpeg, rotationDegrees = 90), null) { true }
        assertAllZero(jpeg)
    }

    /**
     * Writing the orientation into a file with no EXIF makes a second, longer array: both are ours.
     * The second is reached through the source the vault was handed, which reads it in place — so
     * reading it again afterwards reads the array itself.
     */
    @Test
    fun both_our_arrays_are_zeroed_when_the_orientation_needed_a_longer_one() {
        val jpeg = OrientationFixtures.NO_METADATA.copyOf()
        save(CapturedPhoto(jpeg, rotationDegrees = 90), null) { true }

        assertContentEquals(OrientationFixtures.EXIF_MM_6_INSERTED, vault.imported.single().bytes, "the vault got the longer one")
        assertAllZero(jpeg)
        val second = readAll(vault.lastOpenSource!!())
        assertEquals(OrientationFixtures.EXIF_MM_6_INSERTED.size, second.size, "it is the longer array being read")
        assertAllZero(second)
    }

    /**
     * The import's no-spill path depends on this: it holds one source open and asks for a second, so
     * every source must be independent and start from the beginning, however far another has read.
     */
    @Test
    fun the_vault_can_hold_several_sources_at_once_each_reading_the_whole_photo() {
        vault.duringImport = { open ->
            val held = open()
            val partial = kotlinx.io.Buffer()
            held.readAtMostTo(partial, 10)
            assertContentEquals(OrientationFixtures.JFIF_EXIF_MM, readAll(open()), "a second source starts from the start")
            assertContentEquals(
                OrientationFixtures.JFIF_EXIF_MM.copyOfRange(10, OrientationFixtures.JFIF_EXIF_MM.size),
                readAll(held),
                "and the first carries on where it was",
            )
        }
        save(CapturedPhoto(OrientationFixtures.JFIF_EXIF_MM.copyOf(), 0), null) { true }
        assertContentEquals(OrientationFixtures.JFIF_EXIF_MM, vault.imported.single().bytes)
    }

    @Test
    fun our_array_is_zeroed_after_a_failed_import() {
        vault.nextResult = { Outcome.Err(dev.ehsanfahad.darkchamber.core.model.VaultError.Io(IllegalStateException("disk"))) }
        val jpeg = OrientationFixtures.JFIF_EXIF_MM.copyOf()
        assertIs<Outcome.Err>(save(CapturedPhoto(jpeg, 90), null) { true })
        assertAllZero(jpeg)
    }

    /** An import abandoned by a lock is still a return through the same path — and still cleared. */
    @Test
    fun our_array_is_zeroed_even_if_the_import_throws() {
        vault.duringImport = { throw IllegalStateException("session closed") }
        val jpeg = OrientationFixtures.JFIF_EXIF_MM.copyOf()
        runCatching { save(CapturedPhoto(jpeg, 90), null) { false } }
        assertAllZero(jpeg)
    }

    private fun readAll(source: kotlinx.io.RawSource): ByteArray {
        val buffer = kotlinx.io.Buffer()
        source.use { do { val n = it.readAtMostTo(buffer, 8192) } while (n >= 0) }
        return buffer.readByteArray()
    }

    private fun assertAllZero(bytes: ByteArray) {
        val firstLive = bytes.indexOfFirst { it != 0.toByte() }
        assertEquals(-1, firstLive, "byte $firstLive of ${bytes.size} still holds plaintext")
    }
}
