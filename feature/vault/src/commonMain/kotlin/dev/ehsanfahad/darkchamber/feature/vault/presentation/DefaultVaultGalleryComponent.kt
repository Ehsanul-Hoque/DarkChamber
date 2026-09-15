package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.ehsanfahad.darkchamber.core.logging.logVaultError
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlin.coroutines.CoroutineContext

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
    private val imports: VaultImports,
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

        // The batch runs on the session, not here: this only watches it. The component can be
        // destroyed and rebuilt mid-import and will simply pick the state back up.
        imports.state
            .onEach(::onImportState)
            .launchIn(scope)
    }

    override fun importFile() {
        // The coordinator holds the double-tap guard: it is the thing that knows whether a batch is
        // already running, and it stays right whether or not this screen was rebuilt in between.
        imports.importFiles()
    }

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
        // A summary the user has acknowledged must not come back the moment the state re-emits.
        imports.acknowledge()
    }

    private fun onImportState(batch: ImportState) {
        val importing = batch is ImportState.Picking || batch is ImportState.Running
        // A batch that finished with unreadable files raises the banner once. Everything richer —
        // the count line, the per-file bar, the summary — is the screen's to render from
        // [VaultImports.state] directly; duplicating it into GalleryState would be two copies of the
        // same truth, one of them always a frame behind.
        val failed = (batch as? ImportState.Finished)?.failed.orEmpty()
        failed.forEach { logVaultError(IMPORT_STAGE, it.error) }

        _state.value = _state.value.copy(
            importing = importing,
            error = if (failed.isEmpty()) _state.value.error else GalleryError.ImportFailed,
        )
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
