package dev.ehsanfahad.darkchamber.feature.vault.api

import dev.ehsanfahad.darkchamber.core.model.VaultError

/**
 * What an import batch is doing, as much as a screen needs to draw it.
 *
 * Sealed so rendering has to branch: an unsized file and a sized one are genuinely different
 * pictures, and a summary is not a progress bar with the numbers filled in.
 */
sealed interface ImportState {
    /** No batch. Nothing to show. */
    data object Idle : ImportState

    /** The picker is up. On Android the app is stopped and the session is holding its lock. */
    data object Picking : ImportState

    /**
     * A file is being imported. [index] is zero-based, so a screen shows `index + 1` of [total].
     *
     * [stage] is which part of that a screen may name — waiting for the file, copying it in,
     * encrypting, finishing. It exists because the two unmeasurable stages are exactly the two that
     * used to look like nothing was happening, one before any byte existed and one after the bar had
     * reached its own end.
     *
     * [fileFraction] runs 0 → 1 across the current [stage], not across the whole file: the stages
     * divide by different numbers, and two of them divide by nothing. It is **null** whenever there
     * is nothing honest to divide by — an unmeasurable stage, or a platform that never said how big
     * the file is. Draw an indeterminate indicator, never a bar against a guessed total
     * (hard rule #6).
     *
     * [stopping] means the user has asked the batch to stop and it has not stopped yet. It is a
     * state of its own because the gap is real and can be long: a stop takes effect on the next read
     * of the payload, and a file a cloud provider is still fetching can sit inside one blocking read
     * for minutes. Without it the screen would go on offering a Stop button that has already been
     * pressed, which reads as the tap having been missed.
     */
    data class Running(
        val index: Int,
        val total: Int,
        val name: String,
        val fileFraction: Float?,
        // Defaulted to the ordinary case, the same way `stopping` is: most of every import is the
        // encrypt, and a test about something else should not have to name a stage to say so.
        val stage: ImportStage = ImportStage.ENCRYPTING,
        val stopping: Boolean = false,
    ) : ImportState {
        // The name is decrypted vault content; the generated toString would print it into any log
        // line that renders this state (hard rule #4).
        override fun toString(): String =
            "Running(index=$index, total=$total, name=${name.length} chars, " +
                "stage=$stage, fileFraction=$fileFraction, stopping=$stopping)"
    }

    /**
     * The batch ended — finished, or cancelled by the user. [imported] is what landed and stayed
     * landed; [failed] is the files that could not be read, which is not the same as the file a
     * cancel interrupted (nothing was wrong with that one, so it is in neither list).
     *
     * [deletableOriginals] is how many of the imported files came from somewhere that will take a
     * delete, and so how many originals the user can be offered the chance to remove. **Zero is the
     * ordinary answer** — every photo-library item, every iOS item and most cloud documents are
     * zero — and it means no offer at all rather than an offer of nothing.
     *
     * A count, not the files themselves: deleting needs the picked file, and a screen must never
     * hold one. The handles stay with the session-scoped batch, which is what makes a lock end the
     * offer along with everything else.
     */
    data class Finished(
        val imported: Int,
        val failed: List<FailedImport>,
        val deletableOriginals: Int = 0,
        val originalDelete: OriginalDelete = OriginalDelete.NotAsked,
    ) : ImportState {
        override fun toString(): String =
            "Finished(imported=$imported, failed=${failed.size}, " +
                "deletableOriginals=$deletableOriginals, originalDelete=$originalDelete)"
    }
}

/**
 * How far the offer to delete the imported originals has got.
 *
 * Sealed because the three cases are different pictures — an offer, a disclosure that has to be
 * answered, and an outcome — and because the one that destroys something must not be reachable by
 * forgetting a flag.
 */
sealed interface OriginalDelete {
    /** No one has asked. The summary may still be *offering*, if there is anything to offer. */
    data object NotAsked : OriginalDelete

    /**
     * The disclosure is up and waiting for an explicit answer.
     *
     * It is not a "are you sure?" guard against a mis-tap: it is where the limits of deleting get
     * stated, so an original deleted without passing through here would be deleted on a promise the
     * user was never shown (hard rule #6, `docs/THREAT_MODEL.md`). There is no way out of it but
     * choosing — the screen gives it no dismissal.
     */
    data object Confirming : OriginalDelete

    /**
     * The answer was yes and the deletes are running.
     *
     * A state of its own rather than a flag, for the same reason a stopping import has one: the gap
     * is real — a batch of provider calls takes seconds for twenty documents — and the disclosure is
     * still on screen throughout it. Without this, both of its buttons stay live over a decision
     * that has already been acted on, and **Keep them** would close a dialog while the files it
     * promised to keep were already going.
     *
     * Deliberately carries no progress. There is nothing honest to count: the platform decides when
     * each delete returns, and a bar against a file count would move in lurches that mean nothing.
     */
    data object Deleting : OriginalDelete

    /**
     * What came of it: [deleted] of [requested] originals actually went.
     *
     * The two numbers differ whenever a provider refuses a delete it advertised — a document that
     * moved, a grant that no longer covers it. Reporting only the intent would tell someone a file
     * is gone while it is still sitting there.
     */
    data class Done(val deleted: Int, val requested: Int) : OriginalDelete
}

/** A file the batch could not import, and why. */
data class FailedImport(val name: String, val error: VaultError) {
    override fun toString(): String = "FailedImport(name=${name.length} chars, error=$error)"
}
