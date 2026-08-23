package dev.ehsanfahad.darkchamber.core.storage

import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The app-private vault storage facade — the one module permitted to touch filesystem APIs
 * (docs/engineering/MODULES.md §core). Owns the on-disk layout (docs/ARCHITECTURE.md §4):
 *
 * ```
 * root/
 *  ├─ objects/ab/cd/<hexId>.dchm   object blob store  (sharded)
 *  ├─ thumbs/<hexId>.tdchm         thumbnail store    (flat)
 *  ├─ keys/mk.wrapped              wrapped-key blob
 *  └─ tmp/                         scratch            (purged on lock/launch)
 * ```
 *
 * `.dchm` bytes flow through here as opaque sources/sinks — storage never sees ciphers
 * (no `core:crypto` dependency; the codec composes over storage at the call site, spec D6).
 *
 * **Precondition (D1/D3):** [root] is an app-provided, backup-excluded directory. Platform
 * root-resolution (Android `filesDir` / iOS `Documents`) and the iOS `isExcludedFromBackup`
 * runtime call are the app cycle's job (cycle 5); this facade takes the resolved root as an
 * injected [Path], which makes the whole store host- and iOS-sim-testable against a temp root.
 *
 * @param root the resolved, backup-excluded vault directory.
 * @param fs the filesystem to operate on; defaults to [SystemFileSystem] (a param only so a
 *   test can substitute a fake — the real system filesystem on a temp root is the default path).
 */
class VaultStorage(
    private val root: Path,
    private val fs: FileSystem = SystemFileSystem,
) {
    init {
        // Ensure the four layout dirs exist (idempotent — mustCreate = false).
        fs.createDirectories(Path(root, "objects"))
        fs.createDirectories(Path(root, "thumbs"))
        fs.createDirectories(Path(root, "keys"))
        fs.createDirectories(Path(root, "tmp"))
    }
}
