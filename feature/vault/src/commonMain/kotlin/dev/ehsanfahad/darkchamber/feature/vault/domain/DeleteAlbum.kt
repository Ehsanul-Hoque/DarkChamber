package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Delete an album. **Its items are kept** — they become unfiled.
 *
 * No result type of its own, unlike creating and renaming: there is no text to validate, so an
 * [Outcome] already says everything there is to say. Inventing a third three-case enum to match its
 * neighbours would be symmetry for its own sake.
 *
 * ### What a failure here means, and what it does not
 *
 * The vault does this as **two writes** — unfile the items, then delete the album row — because the
 * catalog exposes no transaction spanning both. So a failure is not necessarily a no-op: interrupted
 * between them, the items are already unfiled and the album is still there, holding nothing. That is
 * the deliberate order (the alternative strands items in an album that no longer exists), and it is
 * why the copy for this failure must not claim nothing changed.
 */
internal class DeleteAlbum(
    private val vault: VaultItems,
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {
    suspend operator fun invoke(id: ByteArray): Outcome<Unit> =
        withContext(ioContext) { vault.deleteAlbum(id) }
}
