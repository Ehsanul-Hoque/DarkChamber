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
     * A file is being encrypted. [index] is zero-based, so a screen shows `index + 1` of [total].
     *
     * [fileFraction] runs 0 → 1 across the whole of one file, or is **null** when the platform never
     * said how big the file is — draw an indeterminate indicator, never a bar against a guessed
     * total (hard rule #6).
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
        val stopping: Boolean = false,
    ) : ImportState {
        // The name is decrypted vault content; the generated toString would print it into any log
        // line that renders this state (hard rule #4).
        override fun toString(): String =
            "Running(index=$index, total=$total, name=${name.length} chars, " +
                "fileFraction=$fileFraction, stopping=$stopping)"
    }

    /**
     * The batch ended — finished, or cancelled by the user. [imported] is what landed and stayed
     * landed; [failed] is the files that could not be read, which is not the same as the file a
     * cancel interrupted (nothing was wrong with that one, so it is in neither list).
     */
    data class Finished(val imported: Int, val failed: List<FailedImport>) : ImportState {
        override fun toString(): String = "Finished(imported=$imported, failed=${failed.size})"
    }
}

/** A file the batch could not import, and why. */
data class FailedImport(val name: String, val error: VaultError) {
    override fun toString(): String = "FailedImport(name=${name.length} chars, error=$error)"
}
