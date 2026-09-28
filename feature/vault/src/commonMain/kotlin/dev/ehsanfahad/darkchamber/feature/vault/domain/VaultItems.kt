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
     *
     * [isActive] going false aborts within a read rather than at the end of the file: the encrypt is
     * blocking, so a caller that waited for the loop to notice would wait out the rest of a video.
     */
    fun import(
        name: String,
        mime: String,
        onProgress: (Long) -> Unit = {},
        isActive: () -> Boolean = { true },
        openSource: () -> RawSource,
    ): Outcome<ByteArray>

    /**
     * The stored preview for [id], decrypted into memory — or `Ok(null)` when the item has none,
     * which is the ordinary state for every non-image and everything imported before previews
     * existed.
     *
     * Absence is **not** an error, and an error is not absence: a preview whose bytes fail their
     * integrity tag comes back as `Err`, because that is a tampering signal and drawing the same
     * fallback badge an unsupported type gets would hide it.
     */
    fun thumbnail(id: ByteArray): Outcome<ByteArray?>
}
