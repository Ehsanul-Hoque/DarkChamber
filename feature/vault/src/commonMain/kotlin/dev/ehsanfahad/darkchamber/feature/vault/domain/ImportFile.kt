package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.ImportPhase
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.RawSource
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What came of an import attempt. */
internal sealed interface ImportResult {
    data object Imported : ImportResult

    data class Failed(val error: VaultError) : ImportResult

    /**
     * The caller stopped the batch, so this file was not imported and nothing was wrong with it.
     *
     * A case of its own rather than a [Failed] the caller learns to ignore. A stop can land in two
     * places — waiting for the platform to hand the file over, or part-way through the encrypt — and
     * both produce the same fact: this file belongs in neither the imported nor the failed list. The
     * alternative was returning a [Failed] carrying an error nobody must ever render, and relying on
     * every caller to filter it by re-checking the stop flag.
     */
    data object Stopped : ImportResult
}

/**
 * Stream one already-picked file into the vault.
 *
 * Choosing the file is the caller's job: a pick can yield many files, and each of them is one call
 * to this — the step a batch repeats.
 *
 * The picked file is closed in a `finally` on **every** path — success, vault failure, or a throw —
 * because closing releases the platform grant (an Android URI permission, an iOS security-scoped
 * resource) that would otherwise leak for the life of the process.
 *
 * The import is blocking and does real cryptographic work over the whole payload, so it runs on
 * [ioContext], off whatever thread drove the intent.
 *
 * **This is also where the payload is first opened**, which is the one part of an import that cannot
 * be interrupted from inside the vault. See [openAbandonably].
 */
internal class ImportFile(
    private val vault: VaultItems,
    private val ioContext: CoroutineContext = Dispatchers.Default,
    /**
     * How often the wait for a blocked open re-checks whether the caller still wants the file.
     *
     * Against a wait measured in minutes this is invisible, and it buys the stop a place to land
     * that the per-read guard cannot provide, because a blocked open performs no reads.
     */
    private val pollInterval: Duration = 100.milliseconds,
) {
    suspend operator fun invoke(
        picked: PickedFile,
        isActive: () -> Boolean = { true },
        onProgress: (ImportPhase, Long) -> Unit = { _, _ -> },
    ): ImportResult {
        return try {
            val first = try {
                openAbandonably(picked, isActive)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The provider could not produce a stream at all. Reported as the same Io failure
                // the vault used to return when it performed this open itself, so moving the open
                // out here does not change what the user is shown.
                return ImportResult.Failed(VaultError.Io(e))
            } ?: return ImportResult.Stopped

            val outcome = withContext(ioContext) {
                // Named, not positional: the seam grew a parameter between `isActive` and the opener,
                // and a positional call would have passed the opener as the already-open source.
                vault.import(
                    name = picked.name,
                    mime = picked.mime,
                    onProgress = onProgress,
                    isActive = isActive,
                    first = first,
                    openSource = picked::openSource,
                )
            }
            when (outcome) {
                is Outcome.Ok -> ImportResult.Imported
                // An error while the caller no longer wants the file is the stop arriving, not a
                // broken file: the guard throws out of a read and the vault reports it as a failure
                // because that is all it can see. Deciding which it was belongs here, where the stop
                // flag is, rather than in every caller that renders a list of failures.
                is Outcome.Err -> if (isActive()) ImportResult.Failed(outcome.error) else ImportResult.Stopped
            }
        } catch (e: CancellationException) {
            // Same here: a lock cancels the scope mid-import. Rethrow so the caller's coroutine
            // actually stops instead of reporting a phantom failure — the grant still closes below.
            throw e
        } catch (e: Exception) {
            // core:vault returns Outcome rather than throwing, so this is the "should never happen"
            // net that keeps a lower-layer escape from taking the UI down with it.
            ImportResult.Failed(VaultError.Unexpected(e))
        } finally {
            try {
                picked.close()
            } catch (_: Exception) {
                // Releasing the grant is best-effort; a close failure must not mask the real result.
            }
        }
    }

    /**
     * Open the payload, and give up waiting if the caller stops wanting it.
     *
     * Returns the open source, `null` if the wait was abandoned, or throws whatever the open threw.
     *
     * **Why this is not simply `withContext(ioContext) { picked.openSource() }`.** A provider may
     * block inside that call for as long as it likes — Google Drive downloads the entire file there,
     * four to five minutes for a large one — and during that time the import performs no reads, so
     * the per-read stop guard has nothing to land on. The user's tap was therefore visible
     * immediately and had no effect until the download finished.
     *
     * The open runs in a **detached** scope, on purpose. Cancelling it would not stop anything: the
     * work is happening in the provider's process, and cancellation here would only discard the
     * handle we need in order to close it. So the open is allowed to outlive the batch, and even the
     * session — this is the one place in the app that does. What it holds is a descriptor, never key
     * material and never plaintext: an abandoned open is closed unread.
     *
     * **The transfer itself continues.** No API can cancel a provider's download, so the phone
     * finishes fetching a file the user told us to stop. That is a real cost and the honest one:
     * the alternative is waiting it out, which is the defect this exists to fix.
     *
     * The wait polls rather than racing a signal, because the stop flag *is* the signal — the same
     * predicate a lock and a cancel already share, which the vault's guarded source argues should
     * stay singular.
     */
    private suspend fun openAbandonably(picked: PickedFile, isActive: () -> Boolean): RawSource? {
        val scope = CoroutineScope(ioContext + SupervisorJob())
        val opening = scope.async { picked.openSource() }
        try {
            while (true) {
                // Awaited rather than polled for completion, so a local file — which opens in
                // microseconds — resumes the moment it is ready. A loop that slept first and looked
                // afterwards would charge every import the poll interval, which is five seconds
                // across a fifty-file batch for no reason at all.
                //
                // The timeout ends the *waiting*, not the open: the open lives in a scope of its
                // own, so it keeps going and stays closeable.
                withTimeoutOrNull(pollInterval) { opening.await() }?.let { return it }
                if (!isActive()) {
                    abandon(scope, opening)
                    return null
                }
            }
        } catch (e: CancellationException) {
            // A lock tore the scope down while we waited. The open is still out there, so hand it to
            // the closer before unwinding; otherwise the descriptor leaks for the life of the
            // process.
            abandon(scope, opening)
            throw e
        }
    }

    /**
     * Stop waiting, and close whatever the open eventually produces.
     *
     * The closing is a second coroutine in the same detached scope rather than a completion handler,
     * so exactly one thing ever closes the late source and it does so without reaching for
     * experimental APIs to read a completed value.
     */
    private fun abandon(scope: CoroutineScope, opening: Deferred<RawSource>) {
        scope.launch {
            val late = try {
                opening.await()
            } catch (_: Exception) {
                null // the open failed on its own; there is nothing to release
            }
            try {
                late?.close()
            } catch (_: Exception) {
                // Best-effort, and nobody is waiting on the answer: this runs after the batch has
                // already reported itself stopped.
            }
        }
    }
}
