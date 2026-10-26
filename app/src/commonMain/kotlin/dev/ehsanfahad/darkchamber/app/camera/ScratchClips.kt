package dev.ehsanfahad.darkchamber.app.camera

import dev.ehsanfahad.darkchamber.core.storage.ScratchFile
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFile
import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFiles
import dev.ehsanfahad.darkchamber.feature.camera.api.FreeSpace
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoContainer
import kotlinx.io.RawSource
import kotlinx.io.files.Path

/**
 * Each recording's file: a fresh scratch name in the vault's own `tmp/`, which `core:storage` owns —
 * so the camera reads and deletes it without a filesystem API of its own, and a lock's or the next
 * launch's purge of `tmp/` covers it unchanged.
 */
internal class ScratchClips(private val storage: VaultStorage) : ClipFiles {
    override fun newClip(container: VideoContainer): ClipFile =
        ScratchClip(storage.newScratchFile(suffix = ".${container.extension}"), container)
}

/** A [ScratchFile] in the camera's terms. Its path is never logged (hard rule #4). */
internal class ScratchClip(private val scratch: ScratchFile, override val container: VideoContainer) : ClipFile {
    override val path: Path get() = scratch.path

    override fun open(): RawSource = scratch.open()

    override fun size(): Long? = scratch.size()

    // Best effort, as the seam promises: a delete that fails leaves the file to the purge of tmp/ on
    // lock and on the next launch.
    override fun delete() {
        scratch.delete()
    }

    override fun toString(): String = "ScratchClip"
}

/**
 * Free space when the platform module binds no reader: unknown, which refuses to record and says it
 * could not check — the honest answer, rather than a guess that might let a clip fill the disk.
 */
internal object UnknownFreeSpace : FreeSpace {
    override fun availableBytes(): Long? = null
}
