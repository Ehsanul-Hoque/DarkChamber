package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.vault.ImportPhase
import dev.ehsanfahad.darkchamber.feature.vault.api.FailedImport
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportStage
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * How far a batch has got: which file, which stage of it, and how far into that stage.
 *
 * [fileFraction] runs 0 → 1 across the current [stage] — not across the whole file, because the
 * stages divide by different numbers and two of them divide by nothing at all. It is **null**
 * whenever there is nothing honest to divide by: an unmeasurable stage, or a platform that never
 * said how big the file is. Rendered as an indeterminate indicator rather than a bar against a
 * guessed total (hard rule #6).
 */
internal data class ImportProgress(
    val index: Int,
    val total: Int,
    val name: String,
    val stage: ImportStage,
    val fileFraction: Float?,
) {
    // The name is decrypted vault content, so the generated toString would put a file name in any
    // log line that renders this (hard rule #4).
    override fun toString(): String =
        "ImportProgress(index=$index, total=$total, name=${name.length} chars, " +
            "stage=$stage, fileFraction=$fileFraction)"
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
 * Often empty: photo-library items never support deletion and iOS never does, so no offer at all is
 * an ordinary shape for a finished batch. **Cloud documents are not the exception they were assumed
 * to be** — Google Drive advertises deletion, observed on a device — so this list is not a
 * local-files-only affair.
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
 * What a picked file says about itself, read once.
 *
 * A snapshot rather than repeated property access, because on Android each of these is a query into
 * the provider that owns the file. Reading them twice is a second round-trip for an answer that
 * cannot have changed, and reading them on the wrong thread is what this type exists to stop.
 *
 * The name is decrypted vault content, so it must not reach a log line (hard rule #4).
 */
internal data class PickedFileFacts(val name: String, val mime: String, val size: Long?) {
    override fun toString(): String = "PickedFileFacts(name=${name.length} chars, mime=$mime, size=$size)"
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
internal class ImportQueue(
    private val importFile: ImportFile,
    /**
     * Where a picked file's details are read from the platform.
     *
     * On Android a name, a size and a deletable flag are a cursor query into the provider that owns
     * the file — a blocking binder round-trip, and a cloud provider may take its time over one. This
     * loop runs on whatever context the caller drives it from, which is the main thread, so a
     * fifty-file pick was a hundred and fifty of those round-trips on the thread that draws the
     * screen before the first byte was read. A single-file pick never showed it.
     */
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {

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

                // Resolved once, off this thread, before anything is reported about the file. Every
                // later use is this snapshot rather than another look at the provider.
                val facts = withContext(ioContext) { PickedFileFacts(file.name, file.mime, file.size) }

                // Before the first report the file has not been handed over yet, which is the fetch
                // stage by definition — there is nothing to measure because nothing exists to
                // measure. For a local file it lasts one read and nobody sees it.
                onProgress(ImportProgress(index, files.size, facts.name, ImportStage.FETCHING, null))

                // The snapshot goes with the file rather than being re-read downstream: nothing in
                // the seam promises these are cached, and an implementation that queries on every
                // access would otherwise pay for one per progress frame.
                val result = importFile(file, facts, isActive) { phase, bytes ->
                    val stage = phase.toStage()
                    onProgress(
                        ImportProgress(index, files.size, facts.name, stage, facts.size.fractionAt(stage, bytes)),
                    )
                }
                when (result) {
                    is ImportResult.Imported -> {
                        imported++
                        // Asked only of a file that landed, and asked off this thread like the rest:
                        // on Android it is another provider round-trip, even when the answer is
                        // already cached behind the name lookup. Asking about a file we will never
                        // offer would be one spent on nothing.
                        if (withContext(ioContext) { file.deletable }) deletable += file
                    }
                    is ImportResult.Failed -> failed += FailedImport(facts.name, result.error)
                    // Neither list. Nothing was wrong with this file — the batch was stopped while it
                    // was being fetched or encrypted — and telling the user it failed would blame a
                    // file for their own decision. Which of the two it was is settled upstream,
                    // where the stop flag lives.
                    ImportResult.Stopped -> Unit
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
         * The vault's phase in the words a screen uses.
         *
         * Only the last one is renamed: the vault says it is generating a preview, which is true and
         * is not what a user is waiting to hear. What they are told is that the file is nearly in —
         * the bytes are already sealed by this point, and everything after it is a convenience.
         */
        fun ImportPhase.toStage(): ImportStage = when (this) {
            ImportPhase.COPYING -> ImportStage.COPYING
            ImportPhase.ENCRYPTING -> ImportStage.ENCRYPTING
            ImportPhase.PREVIEWING -> ImportStage.FINISHING
        }

        /**
         * The fraction of the **current stage**, or null when there is nothing honest to divide by.
         *
         * The denominators differ because the work does. A copy is one pass over the payload. The
         * container reads it **twice** — size and digest, then the chunk encrypt — so that count runs
         * to `2 × size`, and the user is shown one bar filling once rather than the same file
         * arriving twice. The other two stages have no total at all: nothing has been read yet, or
         * what is being read is the preview encoder's own business.
         *
         * A size of zero draws the indeterminate bar, which is **not** the picker calling an empty
         * file unknown — the picker reports zero as the fact it is. It is that a fraction of zero
         * bytes has no value to take, and an empty file is imported faster than a bar can be seen
         * either way.
         */
        fun Long?.fractionAt(stage: ImportStage, bytes: Long): Float? {
            val size = this ?: return null
            if (size <= 0L) return null
            val passes = when (stage) {
                ImportStage.COPYING -> 1f
                ImportStage.ENCRYPTING -> 2f
                // Nothing to measure: no byte exists yet, or the reads being made have no declared
                // total. A bar drawn here would be invented (hard rule #6).
                ImportStage.FETCHING, ImportStage.FINISHING -> return null
            }
            return (bytes.toFloat() / (passes * size)).coerceIn(0f, 1f)
        }
    }
}
