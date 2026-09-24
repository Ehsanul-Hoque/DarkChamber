package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.feature.vault.api.FailedImport
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile

/**
 * How far a batch has got: which file, and how far into it.
 *
 * [fileFraction] runs 0 → 1 across the whole of one file, or is **null** when the platform never
 * said how big the file is — rendered as an indeterminate indicator rather than a bar against a
 * guessed total (hard rule #6).
 */
internal data class ImportProgress(
    val index: Int,
    val total: Int,
    val name: String,
    val fileFraction: Float?,
) {
    // The name is decrypted vault content, so the generated toString would put a file name in any
    // log line that renders this (hard rule #4).
    override fun toString(): String =
        "ImportProgress(index=$index, total=$total, name=${name.length} chars, fileFraction=$fileFraction)"
}

/**
 * What a finished batch amounts to: how many landed, which ones did not, and whose originals the
 * user can now be offered the chance to delete.
 *
 * [deletableOriginals] holds only files that **both** imported and say their platform will take a
 * delete — the two conditions fail independently and both are ordinary. Offering to remove an
 * original whose import failed, or one a stopped batch never reached, would destroy the user's only
 * copy — the one case in the import path where being wrong costs a file rather than a feature.
 *
 * The handles are **closed** by the time they are here: [ImportFile] releases every grant in a
 * `finally`. Deleting still works, because the grant belongs to the platform host rather than to any
 * stream we opened — the seam documents that as a promise implementations have to keep.
 *
 * Usually empty. Photo-library items never support deletion, iOS never does, and cloud providers
 * commonly do not, so no offer at all is the ordinary shape of a finished batch.
 */
internal data class ImportSummary(
    val imported: Int,
    val failed: List<FailedImport>,
    val deletableOriginals: List<PickedFile> = emptyList(),
) {
    // A PickedFile prints its own name given the chance, so the count is all that may be rendered
    // (hard rule #4).
    override fun toString(): String =
        "ImportSummary(imported=$imported, failed=${failed.size}, deletable=${deletableOriginals.size})"
}

/**
 * Import a picked batch, one file at a time.
 *
 * **Sequential by construction.** Encryption is CPU-bound, so running files in parallel
 * buys little on a phone while multiplying the bounded-RAM guarantee that streaming exists to
 * provide.
 *
 * **A failed file does not end the batch.** One unreadable file out of fifty is recorded and the
 * rest continue: the other forty-nine were chosen just as deliberately. The summary says what
 * landed and what did not, so a single bad file can never present itself as a failed import.
 *
 * **A cancellation is not a failure.** [isActive] going false stops the batch — within a read, not
 * at the end of the current file — and the interrupted file is recorded in neither list: nothing
 * was wrong with it. A lock cancels the coroutine instead, which propagates.
 *
 * Every [PickedFile] holds a platform grant. [ImportFile] closes the one it is handed, on every
 * path; the ones a stopped batch never reached are closed here.
 */
internal class ImportQueue(private val importFile: ImportFile) {

    suspend operator fun invoke(
        files: List<PickedFile>,
        isActive: () -> Boolean = { true },
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportSummary {
        var imported = 0
        val failed = mutableListOf<FailedImport>()
        val deletable = mutableListOf<PickedFile>()

        // Incremented *before* the import runs: from that point the file is ImportFile's to close,
        // so a cancellation mid-file must not close it a second time here (on iOS an unbalanced
        // stopAccessingSecurityScopedResource is a real bug, not a no-op).
        var next = 0
        try {
            while (next < files.size && isActive()) {
                val file = files[next]
                val index = next
                next++

                val total = file.size
                onProgress(ImportProgress(index, files.size, file.name, startingFraction()))

                val result = importFile(file, isActive) { bytes ->
                    onProgress(ImportProgress(index, files.size, file.name, total.fractionAt(bytes)))
                }
                when (result) {
                    is ImportResult.Imported -> {
                        imported++
                        // Asked only of a file that landed. On Android the answer comes from the
                        // column lookup this file's name already forced, so it costs nothing here —
                        // and asking about a file we will never offer would be a provider
                        // round-trip spent on nothing.
                        if (file.deletable) deletable += file
                    }
                    // A file that stopped because the user stopped the batch is not a broken file.
                    is ImportResult.Failed -> if (isActive()) failed += FailedImport(file.name, result.error)
                }
            }
        } finally {
            for (i in next until files.size) closeQuietly(files[i])
        }

        return ImportSummary(imported = imported, failed = failed, deletableOriginals = deletable)
    }

    private fun closeQuietly(file: PickedFile) {
        try {
            file.close()
        } catch (_: Exception) {
            // Releasing a grant is best-effort, and this runs on the way out of a batch that is
            // already ending — a close failure must not replace the reason it ended.
        }
    }

    private companion object {
        /**
         * Always indeterminate: a file that has not yet reported a single byte has no fraction to
         * draw, and a confident `0f` claims otherwise.
         *
         * This is not pedantry about one frame. Between this emission and the first counted byte
         * sits everything the platform does before we can read the file at all — and for a cloud
         * document the provider downloads on demand, that is the *whole* download. A determinate bar
         * pinned at 0% for minutes reads as "stuck"; an indeterminate one reads as "working", which
         * is the only claim we can support (hard rule #6). For a local file this lasts one read and
         * nobody sees it.
         *
         * The fetch still goes uncounted, so the bar has no way to move during it — this only stops
         * it lying. Counting it means teaching the import path to report how many passes a payload
         * will take, since only it knows whether the source spilled.
         */
        fun startingFraction(): Float? = null

        /**
         * The container reads each payload **twice** — size and digest, then the chunk encrypt — so
         * the byte count runs to `2 × size`. The user is shown one bar filling once, not the same
         * file arriving twice.
         *
         * A size of zero draws the indeterminate bar, which is **not** the picker calling an empty
         * file unknown — the picker reports zero as the fact it is. It is that a fraction of zero
         * bytes has no value to take, and an empty file is imported faster than a bar can be seen
         * either way.
         */
        fun Long?.fractionAt(bytes: Long): Float? {
            val size = this ?: return null
            if (size <= 0L) return null
            return (bytes.toFloat() / (2f * size)).coerceIn(0f, 1f)
        }
    }
}
