package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.ehsanfahad.darkchamber.core.logging.logVaultError
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.domain.ImportFile
import dev.ehsanfahad.darkchamber.feature.vault.domain.ImportResult
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The gallery's state holder, in the session gate's presentation style
 * (docs/engineering/PATTERNS.md §3): the component *is* the holder, exposing [Value] plus named
 * intents on a lifecycle-scoped coroutine scope.
 *
 * State holds ids and display metadata only — never decrypted bytes — and the whole component dies
 * with the unlocked subtree on lock, so nothing here needs its own teardown beyond cancelling the
 * scope.
 */
internal class DefaultVaultGalleryComponent(
    componentContext: ComponentContext,
    observeGallery: ObserveGallery,
    private val picker: FilePicker,
    private val runImport: ImportFile,
    mainContext: CoroutineContext = Dispatchers.Main,
) : VaultGalleryComponent, ComponentContext by componentContext {

    // SupervisorJob so one failed intent does not kill the component's other work, but parented to
    // whatever Job the caller passed: the app scopes feature work to the vault session, and
    // cancelling that must cancel this too — before the session it runs against is zeroized.
    private val scope = CoroutineScope(mainContext + SupervisorJob(mainContext[Job]))

    private val _state = MutableValue(GalleryState())
    override val state: Value<GalleryState> = _state

    init {
        doOnDestroy { scope.cancel() }

        observeGallery()
            // A catalog failure arrives as an exception in the flow, not an Outcome (core:vault's
            // documented contract). Report it: an empty list here would render a broken catalog as
            // an empty vault, which is the fail-closed rule's exact target.
            .catch { cause ->
                logVaultError(LISTING_STAGE, VaultError.Unexpected(cause))
                _state.value = _state.value.copy(loading = false, error = GalleryError.ListingFailed)
            }
            .onEach(::onItems)
            .launchIn(scope)
    }

    override fun importFile() {
        // Set synchronously so a double tap cannot open two pickers — the guard has to hold before
        // the coroutine is even scheduled.
        if (_state.value.importing) return
        _state.value = _state.value.copy(importing = true, error = null)

        scope.launch {
            val picked = try {
                picker.pickFiles()
            } catch (e: CancellationException) {
                // The unlocked subtree was destroyed (a lock) while the picker was up. Cancellation
                // is not an import failure and must not be swallowed into one.
                throw e
            } catch (e: Exception) {
                // The picker could not even be shown — no grant was taken, nothing to close.
                logVaultError(IMPORT_STAGE, VaultError.Unexpected(e))
                _state.value = _state.value.copy(importing = false, error = GalleryError.ImportFailed)
                return@launch
            }

            // An empty pick is the user backing out. Not an error, and no banner.
            var failed = false
            for (file in picked) {
                // Sequential, and one bad file does not end the batch: the other forty-nine were
                // still chosen deliberately. The rows themselves arrive through the listing flow,
                // so each import shows up in the grid as it commits.
                when (val result = runImport(file)) {
                    is ImportResult.Imported -> Unit
                    is ImportResult.Failed -> {
                        logVaultError(IMPORT_STAGE, result.error)
                        failed = true
                    }
                }
            }

            _state.value = _state.value.copy(
                importing = false,
                error = if (failed) GalleryError.ImportFailed else _state.value.error,
            )
        }
    }

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun onItems(items: List<GalleryItem>) {
        // A successful emission clears a stale listing error; an import error is the user's to
        // dismiss, so it survives.
        val error = _state.value.error.takeIf { it != GalleryError.ListingFailed }
        _state.value = _state.value.copy(items = items, loading = false, error = error)
    }

    private companion object {
        // Stage labels for the redaction boundary: event names only, never a name, path or id.
        const val LISTING_STAGE = "vault-gallery-listing"
        const val IMPORT_STAGE = "vault-gallery-import"
    }
}
