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
