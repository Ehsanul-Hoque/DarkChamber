package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * iOS vault-root resolver: `<Documents>/vault`. Sets `isExcludedFromBackup = true` on the root —
 * the cycle-3 deferred runtime call that keeps the encrypted vault out of iCloud/iTunes backups.
 * Directory creation runs through `core:storage` (the shared [DirectoryVaultRootResolver] core).
 */
@OptIn(ExperimentalForeignApi::class)
class IosVaultRootResolver : VaultRootResolver {
    override fun resolve(): Outcome<Path> {
        val documents = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ) ?: return Outcome.Err(VaultError.Io(IllegalStateException("Documents directory unavailable")))

        val vaultUrl = documents.URLByAppendingPathComponent("vault", isDirectory = true)
            ?: return Outcome.Err(VaultError.Io(IllegalStateException("vault URL unavailable")))
        val vaultPath = vaultUrl.path
            ?: return Outcome.Err(VaultError.Io(IllegalStateException("vault path unavailable")))

        return DirectoryVaultRootResolver(
            vaultRoot = Path(vaultPath),
            excludeFromBackup = ::excludeFromBackup,
        ).resolve()
    }
}

/** Marks [root] as excluded from iCloud/iTunes backup via its `NSURL` resource value. */
@OptIn(ExperimentalForeignApi::class)
private fun excludeFromBackup(root: Path): Outcome<Unit> {
    val url = NSURL.fileURLWithPath(root.toString())
    val ok = url.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    return if (ok) {
        Outcome.Ok(Unit)
    } else {
        Outcome.Err(VaultError.Io(IllegalStateException("isExcludedFromBackup failed")))
    }
}
