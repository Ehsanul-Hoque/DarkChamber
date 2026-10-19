package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import kotlinx.io.RawSource

/**
 * The camera's view of the vault — the repository interface its use cases depend on
 * (docs/engineering/MODULES.md §3: `presentation → domain ← data`).
 *
 * It exists so the use cases can be tested without standing up a session, a keystore, a codec, real
 * storage and a catalog. The one production implementation adapts `core:vault`'s `VaultObjects`,
 * which stays the camera's only route to vault data: no cipher primitive, no filesystem API and no
 * SQLDelight type is reachable from this module.
 */
internal interface CaptureVault {
    /**
     * Encrypt the payload behind [openSource] into the vault as a new item filed in [albumId]
     * (`null` leaves it unfiled), returning its id.
     *
     * Same contract as `VaultObjects.importObject`: [openSource] should yield a fresh, independent
     * source on every call — the import opens it more than once, and a source that cannot be
     * re-opened is spilled to app-private scratch first, which a capture must never need. [isActive]
     * is polled on every read; `false` abandons the import, leaving nothing behind.
     */
    fun import(
        kind: Int,
        name: String,
        mime: String,
        albumId: ByteArray?,
        isActive: () -> Boolean,
        openSource: () -> RawSource,
    ): Outcome<ByteArray>
}
