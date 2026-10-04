package dev.ehsanfahad.darkchamber.feature.vault.data

import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.vault.ImportPhase
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems
import kotlinx.coroutines.flow.Flow
import kotlinx.io.RawSource

/**
 * The production [VaultItems], adapting `core:vault`'s `VaultObjects` — resolved from the session
 * scope, so it exists only while a vault is unlocked.
 *
 * Everything imported here is a [VaultItemKind.FILE]; notes arrive as their own kind in a later
 * phase. Deliberately thin: the encryption, the DEK lifecycle, the catalog row and the one-shot
 * source spill all live in `core:vault`, and duplicating any of it here would put vault rules in a
 * feature.
 */
internal class VaultObjectsItems(private val objects: VaultObjects) : VaultItems {

    override fun observe(filter: ItemFilter): Flow<List<VaultItemSummary>> =
        objects.observeItems(filter)

    override fun import(
        name: String,
        mime: String,
        albumId: ByteArray?,
        onProgress: (ImportPhase, Long) -> Unit,
        isActive: () -> Boolean,
        first: RawSource?,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> =
        objects.importObject(
            kind = VaultItemKind.FILE.code,
            name = name,
            mime = mime,
            albumId = albumId,
            onProgress = onProgress,
            isActive = isActive,
            first = first,
            openSource = openSource,
        )

    override fun thumbnail(id: ByteArray): Outcome<ByteArray?> = objects.openThumbnail(id)
}
