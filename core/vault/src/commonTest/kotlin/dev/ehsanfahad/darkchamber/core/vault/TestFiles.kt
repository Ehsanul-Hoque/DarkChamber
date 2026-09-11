package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Tear down a temp vault root. Shared by the module's tests, which each build a `VaultStorage` over
 * their own directory under the system temp dir.
 */
internal fun deleteRecursively(path: Path) {
    val md = SystemFileSystem.metadataOrNull(path) ?: return
    if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
    SystemFileSystem.delete(path, mustExist = false)
}
