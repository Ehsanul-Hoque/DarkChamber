package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Make a new album out of what the user typed.
 *
 * ### Why the name is validated here and not in the vault
 *
 * `core:vault` requires a usable name as a **precondition** and throws otherwise, because by the
 * time a name reaches it the decision has been made. This is the layer that makes it: it is the one
 * that can put a message next to the field, and the one that knows the text came from a human rather
 * than from code.
 *
 * So a blank name is an ordinary answer here ([CreateAlbumResult.NameMissing]) and a crash one layer
 * down. Both are the same rule, stated once in each place it means something different.
 *
 * ### Trimmed, not merely rejected
 *
 * Surrounding whitespace is removed before the name is stored. An album called `"Holiday "` is
 * indistinguishable on screen from `"Holiday"` and sorts away from it, which is a difference the user
 * cannot see and did not ask for. A name of nothing *but* whitespace therefore reduces to nothing and
 * is refused — three spaces is not a name, and an album wearing one cannot be identified or described
 * to anyone.
 */
internal class CreateAlbum(
    private val vault: VaultItems,
    // Where the create runs. It seals a name and writes a catalog row — small, but both are blocking
    // work that has no business on the thread drawing the grid. Injected for the same reason the
    // import's is: a host test has to be able to drive it to completion deterministically.
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {
    suspend operator fun invoke(name: String): CreateAlbumResult {
        val usable = name.trim()
        if (usable.isEmpty()) return CreateAlbumResult.NameMissing

        return when (val made = withContext(ioContext) { vault.createAlbum(usable) }) {
            is Outcome.Ok -> CreateAlbumResult.Created(made.value)
            is Outcome.Err -> CreateAlbumResult.Failed(made.error)
        }
    }
}

/**
 * What came of a create. Three outcomes rather than an `Outcome`, because a name the user has not
 * finished typing is **not a vault failure** — it is something the screen can fix by itself, and
 * flattening the two would mean either inventing a [VaultError] for a UI rule or showing a vault
 * error for an empty text field.
 */
internal sealed interface CreateAlbumResult {

    /** The album exists. The listing will carry it on its next emission; nothing here has to insert it. */
    class Created(val id: ByteArray) : CreateAlbumResult

    /** Nothing usable was typed. No call was made, so there is nothing to undo. */
    data object NameMissing : CreateAlbumResult

    /**
     * The vault refused. [error] is for the log and for deciding what to say — never for printing
     * raw, and never alongside the name that was being created (hard rule 4).
     */
    class Failed(val error: VaultError) : CreateAlbumResult
}
