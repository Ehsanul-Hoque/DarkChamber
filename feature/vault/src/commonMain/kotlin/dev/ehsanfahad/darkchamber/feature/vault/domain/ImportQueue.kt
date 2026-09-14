package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.VaultError
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

/** A file the batch could not import, and why. */
internal data class FailedFile(val name: String, val error: VaultError) {
    override fun toString(): String = "FailedFile(name=${name.length} chars, error=$error)"
}

/** What a finished batch amounts to: how many landed, and which ones did not. */
internal data class ImportSummary(val imported: Int, val failed: List<FailedFile>) {
    override fun toString(): String = "ImportSummary(imported=$imported, failed=${failed.size})"
}

/**
 * Import a picked batch, one file at a time.
 *
 * **Sequential by construction** (spec D2). Encryption is CPU-bound, so running files in parallel
 * buys little on a phone while multiplying the bounded-RAM guarantee that streaming exists to
 * provide.
 *
 * **A failed file does not end the batch.** One unreadable file out of fifty is recorded and the
 * rest continue: the other forty-nine were chosen just as deliberately. The summary says what
 * landed and what did not, so a single bad file can never present itself as a failed import.
 *
 * **A cancellation is not a failure.** A lock (or a user cancel) cancels the coroutine, which
 * propagates — recording it as a failed file would tell the user their photo could not be imported
 * when in fact they locked the vault.
 *
 * Every [PickedFile] holds a platform grant. [ImportFile] closes the one it is handed, on every
 * path; the ones a stopped batch never reached are closed here.
 */
internal class ImportQueue(private val importFile: ImportFile) {

    suspend operator fun invoke(
        files: List<PickedFile>,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportSummary {
        var imported = 0
        val failed = mutableListOf<FailedFile>()

        // Incremented *before* the import runs: from that point the file is ImportFile's to close,
        // so a cancellation mid-file must not close it a second time here (on iOS an unbalanced
        // stopAccessingSecurityScopedResource is a real bug, not a no-op).
        var next = 0
        try {
            while (next < files.size) {
                val file = files[next]
                val index = next
                next++

                val total = file.size
                onProgress(ImportProgress(index, files.size, file.name, total.startingFraction()))

                val result = importFile(file) { bytes ->
                    onProgress(ImportProgress(index, files.size, file.name, total.fractionAt(bytes)))
                }
                when (result) {
                    is ImportResult.Imported -> imported++
                    is ImportResult.Failed -> failed += FailedFile(file.name, result.error)
                }
            }
        } finally {
            for (i in next until files.size) closeQuietly(files[i])
        }

        return ImportSummary(imported = imported, failed = failed)
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
        /** Known size ⇒ we can start at zero; unknown ⇒ nothing honest to show but motion. */
        fun Long?.startingFraction(): Float? = if (this != null && this > 0L) 0f else null

        /**
         * The container reads each payload **twice** — size and digest, then the chunk encrypt — so
         * the byte count runs to `2 × size`. The user is shown one bar filling once, not the same
         * file arriving twice.
         */
        fun Long?.fractionAt(bytes: Long): Float? {
            val size = this ?: return null
            if (size <= 0L) return null
            return (bytes.toFloat() / (2f * size)).coerceIn(0f, 1f)
        }
    }
}
