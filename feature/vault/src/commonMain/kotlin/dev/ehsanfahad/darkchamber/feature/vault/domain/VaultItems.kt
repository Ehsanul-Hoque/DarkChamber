package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.ImportPhase
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
    /**
     * The live listing, re-emitting on every catalog change. [filter] chooses what it covers — the
     * whole vault, one album, or the items in no album.
     *
     * The three cases are a filter, not a destination: unlike an album id being *assigned*, where
     * `null` means "unfile these", there is no value of a nullable id that means "the unfiled ones"
     * — which is why this is a type and not a parameter that can be left out.
     */
    fun observe(filter: ItemFilter): Flow<List<VaultItemSummary>>

    /**
     * Stream-encrypt a payload into the vault, returning the new object's id.
     *
     * [onProgress] receives the phase the import is in and the bytes read so far within it. The
     * encrypt phase runs to `2 × size` because the container reads its payload twice; the other
     * phases divide by something else, or by nothing at all. Turning any of that into something a
     * user sees is the caller's job — `core:vault` owns no total and no percentage, and the phase is
     * there because which denominator applies is the one part only it can know.
     *
     * [isActive] going false aborts within a read rather than at the end of the file: the encrypt is
     * blocking, so a caller that waited for the loop to notice would wait out the rest of a video.
     *
     * [first] is an already-open source over the same payload, standing in for the first open the
     * vault would otherwise perform. It is how a caller keeps a provider's blocking open somewhere it
     * can be abandoned: the vault is synchronous, so a wait inside it cannot be given up on.
     * **Ownership passes with it** — the vault closes it on every path, so a caller must not. `null`
     * leaves the vault to open the payload itself, which is right for a caller with nothing to
     * abandon.
     */
    fun import(
        name: String,
        mime: String,
        onProgress: (ImportPhase, Long) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
        first: RawSource? = null,
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
