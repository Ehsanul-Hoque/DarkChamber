package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** What came of an import attempt. Cancelling is its own outcome, distinct from success and failure. */
internal sealed interface ImportResult {
    data object Imported : ImportResult

    /** The user dismissed the picker. Nothing happened; nothing to report. */
    data object Cancelled : ImportResult

    data class Failed(val error: VaultError) : ImportResult
}

/**
 * Pick a file and stream it into the vault.
 *
 * The picked file is closed in a `finally` on **every** path — success, vault failure, or a throw —
 * because closing releases the platform grant (an Android URI permission, an iOS security-scoped
 * resource) that would otherwise leak for the life of the process.
 *
 * The import itself is blocking and does real cryptographic work over the whole payload, so it runs
 * on [ioContext], off whatever thread drove the intent. The picker stays on the caller's context:
 * presenting UI is a main-thread affair.
 */
internal class ImportFile(
    private val picker: FilePicker,
    private val vault: VaultItems,
    private val ioContext: CoroutineContext = Dispatchers.Default,
) {
    suspend operator fun invoke(): ImportResult {
        val picked = try {
            picker.pickFile() ?: return ImportResult.Cancelled
        } catch (e: CancellationException) {
            // The unlocked subtree was destroyed (a lock) while the picker was up. Coroutine
            // cancellation is not an import failure and must not be swallowed into one.
            throw e
        } catch (e: Exception) {
            // The picker could not even be shown — no grant was taken, so there is nothing to close.
            return ImportResult.Failed(VaultError.Unexpected(e))
        }

        return try {
            when (val result = withContext(ioContext) { vault.import(picked.name, picked.mime, picked::openSource) }) {
                is Outcome.Ok -> ImportResult.Imported
                is Outcome.Err -> ImportResult.Failed(result.error)
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
