package dev.ehsanfahad.darkchamber.core.storage

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Prepares the on-disk vault layout root: creates `<root>` and `<root>/db/` (idempotent) and
 * returns `<root>`. This is the one filesystem-touching step of app root resolution — kept
 * here because `core:storage` is the only module allowed to touch filesystem APIs (MODULES.md
 * §core). The platform base-path resolution (Android `filesDir` / iOS `Documents`) and the iOS
 * `isExcludedFromBackup` attribute call stay in `:app`.
 *
 * `db/` is `core:db`'s catalog directory — a sibling of the `VaultStorage` subdirs
 * (`objects/ thumbs/ keys/ tmp/`), which `VaultStorage` creates itself when constructed on [root].
 *
 * @param root the resolved vault directory to create.
 * @param fs the filesystem to operate on; defaults to [SystemFileSystem] (a param only so a test
 *   can substitute a fake).
 */
fun prepareVaultRoot(root: Path, fs: FileSystem = SystemFileSystem): Outcome<Path> =
    try {
        fs.createDirectories(root)
        fs.createDirectories(Path(root, "db"))
        Outcome.Ok(root)
    } catch (e: Exception) {
        Outcome.Err(VaultError.Io(e))
    }
