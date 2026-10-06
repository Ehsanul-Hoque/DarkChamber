package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Give an album a different name.
 *
 * The same name rule as creating — [usableAlbumName] — because it is the same question about the same
 * kind of text. What is *not* shared is the failure: a rename that does not land leaves the album
 * under its old name, which is a materially different thing to say than "nothing was created", and
 * the surface has to be able to say the right one.
 *
 * Renaming re-seals only the name. Nothing about the album's items changes, and nothing is
 * re-encrypted — which is why this is as cheap as creating one.
 */
internal class RenameAlbum(
    private val vault: VaultItems,
    // Where the write runs, off the thread drawing the grid it will change. Injected for the same
    // reason every other one here is: a host test has to be able to drive it to completion.
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {
    suspend operator fun invoke(id: ByteArray, name: String): RenameAlbumResult {
        val usable = usableAlbumName(name) ?: return RenameAlbumResult.NameMissing

        return when (val done = withContext(ioContext) { vault.renameAlbum(id, usable) }) {
            is Outcome.Ok -> RenameAlbumResult.Renamed
            is Outcome.Err -> RenameAlbumResult.Failed(done.error)
        }
    }
}

/** What came of a rename. Three outcomes for the reason [CreateAlbumResult] has three. */
internal sealed interface RenameAlbumResult {

    /** The album answers to the new name. The listing will carry it on its next emission. */
    data object Renamed : RenameAlbumResult

    /** Nothing usable was typed. No call was made, so the album still has the name it had. */
    data object NameMissing : RenameAlbumResult

    /**
     * The vault refused — including an album that is no longer there, which a rename reports rather
     * than quietly turning into a create. [error] is for the log and for choosing what to say, never
     * for printing, and never beside the name (hard rule 4).
     */
    class Failed(val error: VaultError) : RenameAlbumResult
}
