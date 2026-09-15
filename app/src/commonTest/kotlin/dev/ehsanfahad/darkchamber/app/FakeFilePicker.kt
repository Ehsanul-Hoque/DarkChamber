package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * A picker that hands back whatever the test configured, and counts how often it was asked.
 *
 * Most gate tests never drive an import and leave it empty — an empty list is a cancel — but the
 * unlocked subtree constructs the gallery and the import batch, so both seams must be resolvable.
 */
class FakeFilePicker(private val result: List<PickedFile> = emptyList()) : FilePicker {
    var calls = 0
        private set

    override suspend fun pickFiles(): List<PickedFile> {
        calls++
        return result
    }
}

/** The photo half of the same thing. The real pickers live in `androidMain`/`iosMain`. */
class FakePhotoPicker(private val result: List<PickedFile> = emptyList()) : PhotoPicker {
    var calls = 0
        private set

    override suspend fun pickPhotos(): List<PickedFile> {
        calls++
        return result
    }
}

/**
 * A picked file over bytes held in memory, with a hook that fires as the import opens it — which is
 * how a test locks the vault *during* a specific file rather than between two of them.
 */
class FakePickedFile(
    override val name: String,
    override val mime: String = "application/octet-stream",
    private val data: ByteArray = ByteArray(64) { it.toByte() },
    override val size: Long? = data.size.toLong(),
    private val onOpen: () -> Unit = {},
) : PickedFile {
    var closed = false
        private set

    /** Whether the import ever got as far as reading this file. */
    var opened = false
        private set

    override fun openSource(): RawSource {
        opened = true
        onOpen()
        return Buffer().apply { write(data) }
    }

    override fun close() {
        closed = true
    }
}
