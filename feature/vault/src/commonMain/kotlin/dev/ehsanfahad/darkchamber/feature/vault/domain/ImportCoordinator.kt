package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.logging.logVaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the running import batch and publishes it as one [ImportState] a screen can render.
 *
 * **Bound to the session, not to a screen** (spec D7). It runs on a scope the unlocked subtree owns,
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
        if (_state.value is ImportState.Finished) _state.value = ImportState.Idle
    }

    private fun start(pick: suspend () -> List<PickedFile>) {
        // Set before the coroutine is even scheduled, so a double tap cannot open two pickers.
        if (isRunning) return
        cancelled = false
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

            _state.value = ImportState.Finished(imported = summary.imported, failed = summary.failed)
        }
    }

    private companion object {
        const val IMPORT_STAGE = "vault-import-batch"
    }
}
