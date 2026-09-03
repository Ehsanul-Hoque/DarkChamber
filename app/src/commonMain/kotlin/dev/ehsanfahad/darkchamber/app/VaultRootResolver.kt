package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.flatMap
import dev.ehsanfahad.darkchamber.core.model.map
import dev.ehsanfahad.darkchamber.core.storage.prepareVaultRoot
import kotlinx.io.files.Path

/**
 * Resolves the app-private, backup-excluded vault root, ensuring `<root>` and `<root>/db/` exist.
 *
 * Bound per platform in the Koin platform module: Android from `Context.filesDir`,
 * iOS from `NSDocumentDirectory` with `isExcludedFromBackup` set on the root. The resolved [Path]
 * is the injected precondition that `VaultStorage` and `DriverFactory` consume.
 */
interface VaultRootResolver {
    fun resolve(): Outcome<Path>
}

/**
 * The shared, host/sim-testable core of resolution: creates `<vaultRoot>` + `<vaultRoot>/db/` via
 * `core:storage` (the only module that touches the filesystem), then runs the platform
 * [excludeFromBackup] hook on the created root — short-circuiting to [Outcome.Err] if either step
 * fails (fail-closed). Platform resolvers construct this with their resolved vault path; the
 * default hook is a no-op (Android needs none — the manifest denies backup).
 */
internal class DirectoryVaultRootResolver(
    private val vaultRoot: Path,
    private val excludeFromBackup: (Path) -> Outcome<Unit> = { Outcome.Ok(Unit) },
) : VaultRootResolver {
    override fun resolve(): Outcome<Path> =
        prepareVaultRoot(vaultRoot).flatMap { root ->
            excludeFromBackup(root).map { root }
        }
}
