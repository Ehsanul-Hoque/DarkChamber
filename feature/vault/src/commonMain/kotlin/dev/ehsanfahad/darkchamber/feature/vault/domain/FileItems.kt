package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.AssignSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Move a set of selected items into an album, or out of whatever album they are in.
 *
 * One use case for both directions rather than two, because they are one write with a different
 * argument: an item's album is a nullable column, and `null` is the unfiled state rather than a
 * separate operation. Splitting them would mean two names for one thing and a second place for the
 * same race handling.
 *
 * **Nothing is copied.** An item belongs to at most one album, so filing something already filed moves
 * it; the surface above has to say so, because a user who believes they now hold two encrypted copies
 * has been misled by the word "add".
 *
 * An empty selection is refused here rather than written: the catalog would accept it and notify every
 * listener for a write that changed nothing, and the screen above would report "0 moved" as though
 * something had been attempted.
 */
internal class FileItems(
    private val vault: VaultItems,
    // Where the write runs. One statement, but a blocking one, and the thread it must not run on is the
    // one drawing the listing it will change.
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {
    /**
     * [albumId] is the destination, or `null` to unfile. Returns how many of [ids] actually moved —
     * fewer than asked for when something was shredded between the selection and this call.
     */
    suspend operator fun invoke(ids: List<ByteArray>, albumId: ByteArray?): Outcome<AssignSummary> {
        if (ids.isEmpty()) return Outcome.Ok(AssignSummary(requested = 0, assigned = 0))
        return withContext(ioContext) { vault.assignItems(ids, albumId) }
    }
}
