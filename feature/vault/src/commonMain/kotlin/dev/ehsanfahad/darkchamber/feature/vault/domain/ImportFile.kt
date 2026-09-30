package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.ImportPhase
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** What came of an import attempt. */
internal sealed interface ImportResult {
    data object Imported : ImportResult

    data class Failed(val error: VaultError) : ImportResult
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
 */
internal class ImportFile(
    private val vault: VaultItems,
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {
    suspend operator fun invoke(
        picked: PickedFile,
        isActive: () -> Boolean = { true },
        onProgress: (ImportPhase, Long) -> Unit = { _, _ -> },
    ): ImportResult {
        return try {
            val outcome = withContext(ioContext) {
                vault.import(picked.name, picked.mime, onProgress, isActive, picked::openSource)
            }
            when (outcome) {
                is Outcome.Ok -> ImportResult.Imported
                is Outcome.Err -> ImportResult.Failed(outcome.error)
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
}
