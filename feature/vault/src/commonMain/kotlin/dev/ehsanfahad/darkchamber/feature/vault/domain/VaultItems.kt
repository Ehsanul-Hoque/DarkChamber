package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.io.RawSource

/**
 * The feature's view of the vault — the repository interface the use cases depend on
 * (docs/engineering/MODULES.md §3: `presentation → domain ← data`).
 *
 * It exists so the use cases can be tested without standing up a session, a keystore, a codec, real
 * storage and a catalog. The one production implementation adapts `core:vault`'s `VaultObjects`,
 * which stays the feature's *only* route to vault data: no cipher primitive, no filesystem API and
 * no SQLDelight type is reachable from this module.
 */
internal interface VaultItems {
    /** The live listing, re-emitting on every catalog change. `null` album = the whole vault. */
    fun observe(albumId: ByteArray?): Flow<List<VaultItemSummary>>

    /**
     * Stream-encrypt a payload into the vault, returning the new object's id.
     *
     * [onProgress] receives payload bytes read so far, cumulatively across the container's two
     * passes — so it runs to `2 × size`, and turning that into anything a user sees is the caller's
     * job (`core:vault` owns no notion of a total or a percentage).
     */
    fun import(
        name: String,
        mime: String,
        onProgress: (Long) -> Unit = {},
        openSource: () -> RawSource,
    ): Outcome<ByteArray>
}
