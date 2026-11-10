package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Delete one item for good: crypto-shred it through the vault, off the thread drawing the screen.
 *
 * The vault answers with an outcome; anything it throws instead becomes an `Err`, so the screen can
 * say nothing was deleted rather than closing. A cancellation stays one.
 */
/**
 * Delete the selected items for good, in one write — see [VaultItems.shredMany]. Off the thread drawing
 * the grid; anything the vault throws becomes an `Err`, so the screen says nothing was deleted; a
 * cancellation stays one. An empty selection deletes nothing and touches nothing.
 */
internal class ShredItems(
    private val vault: VaultItems,
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {
    suspend operator fun invoke(ids: List<ByteArray>): Outcome<Long> {
        if (ids.isEmpty()) return Outcome.Ok(0)
        return withContext(ioContext) {
            try {
                vault.shredMany(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Err(VaultError.Unexpected(e))
            }
        }
    }
}

internal class ShredItem(
    private val vault: VaultItems,
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {
    suspend operator fun invoke(id: ByteArray): Outcome<Unit> =
        withContext(ioContext) {
            try {
                vault.shred(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Err(VaultError.Unexpected(e))
            }
        }
}
