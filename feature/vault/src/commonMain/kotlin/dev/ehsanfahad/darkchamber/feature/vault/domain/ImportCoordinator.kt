package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.logging.logVaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.OriginalDelete
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the running import batch and publishes it as one [ImportState] a screen can render.
 *
 * **Bound to the session, not to a screen.** It runs on a scope the unlocked subtree owns,
 * so navigating away — which the albums cycle introduces — cannot cancel an import, while locking
 * still does, because locking cancels that scope. A component that owned the batch would make the
 * two indistinguishable.
 *
 * The consequence to be honest about: when a **lock** ends a batch, this object dies with the
 * session and the user can never be told what happened. Persisting batch state across a lock is not
 * something we will do, so the pre-import notice is the only warning there is — which is exactly why
 * it is load-bearing rather than decoration (spec §4.4).
 */
internal class ImportCoordinator(
    private val photoPicker: PhotoPicker,
    private val filePicker: FilePicker,
    private val queue: ImportQueue,
    private val scope: CoroutineScope,
    // Where a delete runs. Each one is a blocking call into a content provider, which on Android
    // means a binder round-trip per file — fifty of those on the UI thread is a frozen app.
    private val ioContext: CoroutineContext = Dispatchers.Default,
) : VaultImports {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    override val state: StateFlow<ImportState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Flipped by [cancel] and read on every payload read, so a cancel stops within a read instead of
     * at the end of the current file — two minutes, for a large video. Reset as each batch starts.
     *
     * `@Volatile` because the write and the reads are on different threads: [cancel] runs on the UI
     * thread, while the predicate is polled from inside the blocking encrypt on the import
     * dispatcher. Without it there is no happens-before between the two, and a cancel is free to go
     * unnoticed for the rest of the file — the exact wait this flag exists to avoid.
     */
    @Volatile
    private var cancelled = false

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * The picked files whose originals the finished summary is offering to delete — the handles
     * behind [ImportState.Finished.deletableOriginals], which is only their count.
     *
     * They live **here**, on the session-scoped batch, rather than on the screen that draws the
     * offer. Navigating away therefore keeps the offer, and locking ends it: this object
     * dies with the session, taking the handles with it. That is the correct outcome — a lock ends
     * everything about the batch, and an offer to delete someone's file is not a thing to keep
     * across one.
     *
     * Closed by now, all of them, and still usable: the seam promises deletion survives `close()`,
     * because the grant belongs to the platform host rather than to any stream we opened.
     */
    var deletableOriginals: List<PickedFile> = emptyList()
        private set


    override fun importPhotos() = start { photoPicker.pickPhotos() }

    override fun importFiles() = start { filePicker.pickFiles() }

    /** Stop the batch. What has already been imported stays imported. */
    override fun cancel() {
        cancelled = true
        // Say so immediately rather than waiting for the batch to notice. The flag is read on the
        // next payload read, and the platform decides when that is — a file a cloud provider is
        // still fetching can sit inside one blocking read for minutes, reporting nothing. Leaving
        // the card untouched for that long offers a Stop button that has already been pressed.
        val running = _state.value as? ImportState.Running ?: return
        _state.value = running.copy(stopping = true)
    }

    /** Dismiss a finished summary. Anything else is left alone — a running batch is not dismissible. */
    override fun acknowledge() {
        val finished = _state.value as? ImportState.Finished ?: return
        // Not while its deletes are running. Dismissing would not stop them — they run on a list
        // captured before the launch — so it would only hide them, and the outcome would then be
        // reported to nobody.
        if (finished.originalDelete == OriginalDelete.Deleting) return
        // The offer was part of the summary, so dismissing the summary dismisses it. Keeping the
        // handles would leave a delete with nothing left on screen to have authorised it.
        deletableOriginals = emptyList()
        _state.value = ImportState.Idle
    }

    override fun requestOriginalDelete() {
        val finished = _state.value as? ImportState.Finished ?: return
        // Nothing to offer, nothing to disclose. A dialog explaining that there is nothing to do is
        // worse than no dialog, and this is the ordinary case rather than an edge one.
        if (finished.deletableOriginals == 0) return
        if (finished.originalDelete != OriginalDelete.NotAsked) return
        _state.value = finished.copy(originalDelete = OriginalDelete.Confirming)
    }

    override fun confirmOriginalDelete() {
        val finished = _state.value as? ImportState.Finished ?: return
        // The disclosure *is* the authorisation. Reaching a delete without having passed through it
        // would mean deleting someone's file on a warning they were never shown (hard rule #6).
        if (finished.originalDelete != OriginalDelete.Confirming) return

        // Taken now, so a lock — which cancels this scope before the launch below runs — finds
        // nothing left to delete by.
        val originals = deletableOriginals
        // Published *before* the launch, so there is no frame in which the answer has been given
        // and the dialog still offers to take it back. It is also the second-tap guard: every other
        // intent here refuses to act on a state that is already Deleting.
        _state.value = finished.copy(originalDelete = OriginalDelete.Deleting)
        scope.launch {
            val deleted = withContext(ioContext) { originals.count { it.deleteOriginal() } }
            deletableOriginals = emptyList() // asked for once, whatever the provider decided
            // Re-read rather than reusing the captured state: the batch cannot have moved on, but
            // nothing about this coroutine guarantees that, and overwriting a newer state with an
            // older one is how a summary starts describing the wrong batch.
            val current = _state.value as? ImportState.Finished ?: return@launch
            _state.value = current.copy(
                originalDelete = OriginalDelete.Done(deleted = deleted, requested = originals.size),
            )
        }
    }

    override fun keepOriginals() {
        val finished = _state.value as? ImportState.Finished ?: return
        // Only from the disclosure. Once the deletes are running there is nothing left to keep —
        // refusing here is what stops the dialog closing on a promise it can no longer honour.
        if (finished.originalDelete != OriginalDelete.Confirming) return
        // Back to the offer, not past it: they declined this dialog, not the idea.
        _state.value = finished.copy(originalDelete = OriginalDelete.NotAsked)
    }

    private fun start(pick: suspend () -> List<PickedFile>) {
        // Set before the coroutine is even scheduled, so a double tap cannot open two pickers.
        if (isRunning) return
        cancelled = false
        // Whatever the last batch left is no longer what the screen is showing. A handle that
        // outlived its summary would sit behind a count that no longer describes it.
        deletableOriginals = emptyList()
        _state.value = ImportState.Picking

        job = scope.launch {
            val picked = try {
                pick()
            } catch (e: CancellationException) {
                // The session was torn down while the picker was up. Not a failure, and there is no
                // state left to publish — this object is going away with it.
                throw e
            } catch (_: Exception) {
                // The picker could not even be shown: no grant was taken, nothing to close, and
                // nothing imported, so Idle is the honest state. It is also a *silent* one — no
                // banner is raised for a picker that never opened, so the affordance simply looks
                // inert. A known gap rather than a decision; surfacing it needs a state of its own,
                // because "the picker would not open" is not "that file could not be added".
                _state.value = ImportState.Idle
                return@launch
            }

            // An empty pick is the user backing out. Idle, not Finished(0) — a summary reading
            // "imported 0" would look like a failure to someone who simply changed their mind.
            if (picked.isEmpty()) {
                _state.value = ImportState.Idle
                return@launch
            }

            val summary = queue(
                files = picked,
                isActive = { !cancelled },
                onProgress = { progress ->
                    _state.value = ImportState.Running(
                        index = progress.index,
                        total = progress.total,
                        name = progress.name,
                        stage = progress.stage,
                        fileFraction = progress.fileFraction,
                        // Carried on every emission, not just set once by cancel(): the queue keeps
                        // reporting progress for the file it is still finishing, and rebuilding the
                        // state from the progress alone would clear the flag again on the next read.
                        stopping = cancelled,
                    )
                },
            )

            // Logged here, where a batch ends exactly once, rather than where the summary is shown:
            // a screen can be rebuilt and replay this state any number of times, and one failed file
            // is one event however often it is rendered. Redacted either way — the stage tag and the
            // error type, never the file's name.
            summary.failed.forEach { logVaultError(IMPORT_STAGE, it.error) }

            // Retained before the state that advertises them is published, so a screen can never
            // read a count without the handles behind it already being here.
            deletableOriginals = summary.deletableOriginals
            _state.value = ImportState.Finished(
                imported = summary.imported,
                failed = summary.failed,
                deletableOriginals = summary.deletableOriginals.size,
            )
        }
    }

    private companion object {
        const val IMPORT_STAGE = "vault-import-batch"
    }
}
