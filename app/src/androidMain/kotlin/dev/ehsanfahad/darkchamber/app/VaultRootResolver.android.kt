package dev.ehsanfahad.darkchamber.app

import android.content.Context
import dev.ehsanfahad.darkchamber.core.model.Outcome
import kotlinx.io.files.Path

/**
 * Android vault-root resolver: `<filesDir>/vault` on app-private internal storage. No backup call —
 * the manifest already denies OS/cloud backup (cycle 3: `allowBackup=false` + deny-all rules), so
 * the default no-op exclusion hook is correct here.
 */
class AndroidVaultRootResolver(context: Context) : VaultRootResolver {
    private val delegate = DirectoryVaultRootResolver(Path(context.filesDir.absolutePath, "vault"))

    override fun resolve(): Outcome<Path> = delegate.resolve()
}
