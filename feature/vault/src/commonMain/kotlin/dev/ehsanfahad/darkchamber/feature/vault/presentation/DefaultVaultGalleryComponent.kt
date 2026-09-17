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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

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
    // How often a *running* batch is allowed to redraw. The batch reports every buffered read —
    // kilobytes apart — which is far finer than a screen can show or a user can perceive, and
    // recomposing the grid at that rate would cost more than the encrypt does. Throttling here
    // rather than in the core keeps the honest byte count intact for anything else that watches it
    // (spec §6.3). Zero in tests, where every state is worth asserting.
    private val progressInterval: Duration = PROGRESS_INTERVAL,
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
            .throttleProgress(progressInterval)
            .onEach(::onImportState)
            .launchIn(scope)
    }

    // The coordinator holds the double-tap guard: it is the thing that knows whether a batch is
    // already running, and it stays right whether or not this screen was rebuilt in between.
    override fun importPhotos() = imports.importPhotos()

    override fun importFiles() = imports.importFiles()

    override fun cancelImport() = imports.cancel()

    override fun acknowledgeImport() = imports.acknowledge()

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
        // A summary the user has acknowledged must not come back the moment the state re-emits.
        imports.acknowledge()
    }

    /**
     * Let every state through except a flood of [ImportState.Running] updates, of which one per
     * [interval] is enough. A batch ending — or starting, or being cancelled — is never delayed:
     * those are the frames that must land immediately, and there are only ever a handful of them.
     */
    private fun Flow<ImportState>.throttleProgress(interval: Duration): Flow<ImportState> = flow {
        var lastRunning: TimeMark? = null
        collect { value ->
            if (value is ImportState.Running) {
                val previous = lastRunning
                if (previous == null || previous.elapsedNow() >= interval) {
                    lastRunning = TimeSource.Monotonic.markNow()
                    emit(value)
                }
            } else {
                lastRunning = null
                emit(value)
            }
        }
    }

    private fun onImportState(batch: ImportState) {
        // The batch state goes into GalleryState whole rather than being flattened into flags: the
        // screen needs the file count, the name and the fraction, and reducing them here would mean
        // inventing a second vocabulary for the same thing.
        // Not logged here: this runs again every time the state is replayed to a rebuilt screen,
        // and a failed file is one event. The batch logs it once, where the batch ends.
        val failed = (batch as? ImportState.Finished)?.failed.orEmpty()

        _state.value = _state.value.copy(
            batch = batch,
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

        /** Roughly six frames at 60 Hz: fast enough to look continuous, slow enough to be free. */
        val PROGRESS_INTERVAL: Duration = 100.milliseconds
    }
}
