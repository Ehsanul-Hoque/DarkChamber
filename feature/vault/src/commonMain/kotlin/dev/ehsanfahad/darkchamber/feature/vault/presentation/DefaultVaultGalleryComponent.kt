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
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportStage
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformLatest
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
    // How long a pick has to be taking before it is worth saying so. The picker covers this screen a
    // few hundred milliseconds after it is asked for, so a notice published the instant the button
    // is tapped is read by nobody — it is a flicker under a sheet already on its way up. Held back,
    // it appears only when there is a wait to explain, and the wait that matters is the one *after*
    // the user has chosen, while the system materialises the selection (on iOS, a per-asset iCloud
    // download that can run to seconds). Zero in tests.
    private val pickingDelay: Duration = PICKING_DELAY,
    // How long a stage that cannot be measured has to last before it is worth naming. Short enough
    // that a real wait is explained almost at once, long enough that a local file — which passes
    // through both unmeasurable stages in microseconds — never names either. Zero in tests.
    private val stageGrace: Duration = STAGE_GRACE,
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
            .holdBackPicking(pickingDelay)
            .throttleProgress(progressInterval)
            // After the throttle, not before: the announcement it makes later is a single frame the
            // throttle would be entitled to drop, having just let the silent version of the same
            // state through.
            .holdBackUnmeasurableStages(stageGrace)
            .onEach(::onImportState)
            .launchIn(scope)
    }

    // The coordinator holds the double-tap guard: it is the thing that knows whether a batch is
    // already running, and it stays right whether or not this screen was rebuilt in between.
    override fun importPhotos() = imports.importPhotos()

    override fun importFiles() = imports.importFiles()

    override fun cancelImport() = imports.cancel()

    override fun acknowledgeImport() = imports.acknowledge()

    // Forwarded, like every other intent: the batch owns the offer, because the handles behind it
    // must not live on a screen that a lock does not reach.
    override fun requestOriginalDelete() = imports.requestOriginalDelete()

    override fun confirmOriginalDelete() = imports.confirmOriginalDelete()

    override fun keepOriginals() = imports.keepOriginals()

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
        // A summary the user has acknowledged must not come back the moment the state re-emits.
        imports.acknowledge()
    }

    /**
     * Delays [ImportState.Picking] by [after], and drops it entirely if anything supersedes it in
     * the meantime.
     *
     * `transformLatest`, not `transform`: a new state has to **cancel** the pending delay, so a pick
     * that completes quickly shows no notice at all. With a plain `transform` the delay would merely
     * hold the flow up and the stale notice would still arrive afterwards — briefly drawn on top of
     * a batch that has already started, which is worse than the flicker this exists to remove.
     *
     * Only `Picking` is delayed; a running or finished batch is never held back for any reason.
     */
    private fun Flow<ImportState>.holdBackPicking(after: Duration): Flow<ImportState> =
        transformLatest { value ->
            if (value is ImportState.Picking) delay(after)
            emit(value)
        }

    /**
     * Hold back the *name* of a stage that cannot be measured until it has lasted [after].
     *
     * One rule, not two special cases: **a stage with no fraction to draw is announced only once it
     * has lasted; a stage with a denominator is announced at once**, because a moving bar is its own
     * evidence that something is happening.
     *
     * Both unmeasurable stages need it. Waiting for a local file takes microseconds, and every import
     * ends with a preview attempt whether or not a preview is possible — so without this, a batch of
     * twenty-five local photos would flash two labels per file, fifty in a few seconds.
     *
     * `transformLatest`, for the same reason as [holdBackPicking]: a new state must **cancel** the
     * pending announcement, so a stage that did not last is never named at all. The silent version
     * goes out immediately, so the card keeps its file, its count and its indeterminate bar
     * throughout — exactly as it looked before stages existed.
     *
     * **Only a stage that is new is held back.** A batch re-reports the stage it is already in — the
     * stop arriving is one such re-report, and during a long fetch it is the likeliest one — and
     * holding those back would take a label the user is reading back off the screen for [after]
     * before restoring it. The grace exists to keep a label from flashing on, not to make one flash
     * off, so an announcement already made stands until the stage actually changes.
     */
    private fun Flow<ImportState>.holdBackUnmeasurableStages(after: Duration): Flow<ImportState> =
        flow {
            // Inside the returned flow, not captured beside it: a flow may be collected more than
            // once, and two collectors sharing one `announced` would each see the other's stages.
            var announced: ImportStage? = null
            transformLatest { value ->
                val stage = (value as? ImportState.Running)?.stage
                when {
                    // Measurable, or nothing to name: there is no announcement pending and none owed.
                    stage == null || stage.measurable -> {
                        announced = null
                        emit(value)
                    }
                    // Already on screen. Saying it again costs nothing; taking it away costs the
                    // explanation for a wait that is still going on.
                    stage == announced -> emit(value)
                    else -> {
                        emit(value.copy(stage = null))
                        delay(after)
                        announced = stage
                        emit(value)
                    }
                }
            }.collect { emit(it) }
        }

    /**
     * Let every state through except a flood of [ImportState.Running] updates, of which one per
     * [interval] is enough. A batch ending — or starting, or being cancelled — is never delayed:
     * those are the frames that must land immediately, and there are only ever a handful of them.
     */
    private fun Flow<ImportState>.throttleProgress(interval: Duration): Flow<ImportState> = flow {
        var lastRunning: TimeMark? = null
        var wasStopping = false
        var lastStage: ImportStage? = null
        collect { value ->
            if (value is ImportState.Running) {
                val previous = lastRunning
                // The window drops redundant *progress*. A batch turning into stopping is not
                // progress — it is the user's tap arriving, and it may be the last thing this batch
                // says for minutes if the platform is inside a blocking read. Held back, it leaves a
                // Stop button on screen that has already been pressed.
                val becameStopping = value.stopping != wasStopping
                // Nor is a change of stage. It is a different thing being done, and the stage the
                // screen is still showing has an announcement pending behind it: drop the change and
                // that announcement arrives anyway, labelling the import with a stage it has already
                // left. A bar that says it is waiting for a file it is busy encrypting is worse than
                // a bar that says nothing.
                val changedStage = value.stage != lastStage
                if (becameStopping || changedStage || previous == null || previous.elapsedNow() >= interval) {
                    lastRunning = TimeSource.Monotonic.markNow()
                    wasStopping = value.stopping
                    // Updated only alongside an emission, which today is indistinguishable from
                    // updating it on every report: a changed stage always passes, so the last stage
                    // reported and the last stage shown cannot diverge. Kept here because it is the
                    // placement that survives the bypass ever being narrowed — not because anything
                    // currently depends on it, and a test cannot tell the two apart.
                    lastStage = value.stage
                    emit(value)
                }
            } else {
                lastRunning = null
                wasStopping = false
                lastStage = null
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

        /**
         * Longer than a picker takes to cover the screen, short enough that a real wait is explained
         * before it is worried about. Under it, nothing is drawn at all — a notice nobody can finish
         * reading is worse than no notice, and the same warning appears under the progress bar a
         * moment later anyway.
         */
        val PICKING_DELAY: Duration = 450.milliseconds

        /**
         * A wait worth naming is one that lasted.
         *
         * Below this, an import is not waiting on anything a person would notice: a local file is
         * handed over and previewed in microseconds, and naming either stage would put two labels on
         * screen per file — fifty of them across a batch of twenty-five photos, none legible. Above
         * it, something really is holding the import up, and saying which of the two it is turns a
         * bar that cannot move into an explanation.
         */
        val STAGE_GRACE: Duration = 400.milliseconds
    }
}
