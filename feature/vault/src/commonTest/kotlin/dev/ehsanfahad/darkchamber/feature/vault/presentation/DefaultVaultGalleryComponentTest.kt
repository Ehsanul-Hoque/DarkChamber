package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.FakeFilePicker
import dev.ehsanfahad.darkchamber.feature.vault.FakePickedFile
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.FailedImport
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
import dev.ehsanfahad.darkchamber.feature.vault.damagedMeta
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultVaultGalleryComponentTest {

    private val lifecycle = LifecycleRegistry().apply { resume() }

    private fun TestScope.component(
        vault: VaultItems,
        imports: VaultImports = FakeImports(),
        progressInterval: Duration = Duration.ZERO,
        pickingDelay: Duration = Duration.ZERO,
    ): DefaultVaultGalleryComponent {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultVaultGalleryComponent(
            componentContext = DefaultComponentContext(lifecycle),
            observeGallery = ObserveGallery(vault),
            imports = imports,
            mainContext = dispatcher,
            // No throttling by default: these tests are about which states arrive, not how often.
            progressInterval = progressInterval,
            pickingDelay = pickingDelay,
        )
    }

    @Test
    fun the_listing_settles_from_loading_into_items() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)

        // The fake's first emission is the empty list, so the component has already settled.
        assertFalse(component.state.value.loading, "loading clears on the first emission")
        assertEquals(emptyList(), component.state.value.items)

        vault.summaries.value = listOf(summary(byteArrayOf(1), readableMeta("a.txt", "text/plain")))

        val items = component.state.value.items
        assertEquals(1, items.size)
        assertEquals(GalleryItemMeta.Readable("a.txt", "text/plain"), items.single().meta)
    }

    @Test
    fun a_damaged_item_reaches_the_state_rather_than_being_dropped() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)

        vault.summaries.value = listOf(
            summary(byteArrayOf(1), readableMeta()),
            summary(byteArrayOf(2), damagedMeta()),
        )

        val items = component.state.value.items
        assertEquals(2, items.size, "the damaged item is listed")
        assertEquals(GalleryItemMeta.Unreadable, items[1].meta)
    }

    /**
     * A catalog failure arrives as an exception in the flow (core:vault's documented contract). It
     * must surface as an error, never as a silently empty vault.
     */
    @Test
    fun a_failing_listing_surfaces_an_error_instead_of_an_empty_vault() = runTest {
        val component = component(ExplodingVaultItems())

        val state = component.state.value
        assertEquals(GalleryError.ListingFailed, state.error, "a broken catalog is reported, not hidden")
        assertFalse(state.loading, "the listing settled, unsuccessfully")
        assertEquals(emptyList(), state.items)
    }

    @Test
    fun every_import_intent_is_handed_to_the_batch_rather_than_run_here() = runTest {
        val imports = FakeImports()
        val component = component(FakeVaultItems(), imports = imports)

        component.importFiles()
        component.importPhotos()
        component.cancelImport()
        component.acknowledgeImport()

        // The component does not pick, loop or guard against a double tap: the batch outlives it,
        // so the batch is what knows whether one is already running.
        assertEquals(1, imports.fileRequests)
        assertEquals(1, imports.photoRequests)
        assertEquals(1, imports.cancels)
        assertEquals(1, imports.acknowledged)
    }

    @Test
    fun the_running_state_reaches_the_screen_whole() = runTest {
        val imports = FakeImports()
        val component = component(FakeVaultItems(), imports = imports)

        val running = ImportState.Running(index = 11, total = 50, name = "IMG_0042.HEIC", fileFraction = 0.25f)
        imports.emit(running)

        // Not flattened into flags on the way past: the screen needs the count, the name and the
        // fraction, and reducing them here would mean inventing a second vocabulary for the same
        // thing — one of them always a frame behind.
        assertEquals(running, component.state.value.batch)
        assertTrue(component.state.value.importing)
    }

    /**
     * A batch reports every buffered read — kilobytes apart — which is far finer than a screen can
     * show. Throttling belongs here rather than in the core, where the honest byte count has to stay
     * intact for anything else watching it.
     */
    @Test
    fun progress_updates_are_throttled_but_the_batch_ending_is_never_delayed() = runTest {
        val imports = FakeImports()
        // An interval no test can outrun, so every Running after the first is dropped.
        val component = component(FakeVaultItems(), imports = imports, progressInterval = 1.hours)

        imports.emit(ImportState.Running(index = 0, total = 3, name = "a.jpg", fileFraction = 0.1f))
        imports.emit(ImportState.Running(index = 0, total = 3, name = "a.jpg", fileFraction = 0.5f))
        imports.emit(ImportState.Running(index = 1, total = 3, name = "b.jpg", fileFraction = 0.9f))

        val throttled = component.state.value.batch
        assertIs<ImportState.Running>(throttled)
        assertEquals(0.1f, throttled.fileFraction, "later progress inside the window is dropped")

        imports.emit(ImportState.Finished(imported = 3, failed = emptyList()))

        // The frames that matter are never held back: a finished batch must not sit behind a
        // throttle window, or the UI keeps claiming an import is running after it has stopped.
        assertIs<ImportState.Finished>(component.state.value.batch)
        assertFalse(component.state.value.importing)
    }

    /**
     * The throttle exists to drop *progress*, which is redundant between frames. Tapping Stop is not
     * progress — it is a different thing being shown, and it may be the last word the batch gets in
     * for minutes if the platform is sat inside a blocking read. Dropping it leaves a Stop button on
     * screen that has already been pressed.
     */
    @Test
    fun the_throttle_never_swallows_a_batch_turning_into_stopping() = runTest {
        val imports = FakeImports()
        val component = component(FakeVaultItems(), imports = imports, progressInterval = 1.hours)

        imports.emit(ImportState.Running(index = 0, total = 3, name = "a.jpg", fileFraction = 0.1f))
        // Inside the throttle window, and the last thing this batch will say for a long time.
        imports.emit(ImportState.Running(index = 0, total = 3, name = "a.jpg", fileFraction = 0.1f, stopping = true))

        val shown = assertIs<ImportState.Running>(component.state.value.batch)
        assertTrue(shown.stopping, "the stop has to reach the screen even mid-window")
    }

    /**
     * The picker covers the screen a few hundred milliseconds after it is asked for, so a notice
     * published the instant the button is tapped is read by nobody — it is a flicker under a sheet
     * that is already on its way up.
     *
     * Held back, it appears only when there is actually a wait to explain: a picker slow to open, or
     * — the case that matters — the stretch *after* the user has chosen, while the system is still
     * materialising what they picked. On iOS that is a per-asset download and can run to seconds.
     */
    @Test
    fun the_picking_notice_waits_rather_than_flashing_under_the_picker() = runTest {
        val imports = FakeImports()
        val component = component(FakeVaultItems(), imports = imports, pickingDelay = 400.milliseconds)

        imports.emit(ImportState.Picking)

        assertIs<ImportState.Idle>(
            component.state.value.batch,
            "nothing is shown while the picker is on its way up",
        )

        advanceTimeBy(401.milliseconds)
        assertIs<ImportState.Picking>(
            component.state.value.batch,
            "a wait long enough to notice does get explained",
        )
    }

    @Test
    fun a_picker_that_opens_promptly_never_shows_the_notice_at_all() = runTest {
        val imports = FakeImports()
        val component = component(FakeVaultItems(), imports = imports, pickingDelay = 400.milliseconds)

        // Every state the screen was actually asked to draw. Asserting only where it *ended up*
        // would pass whether or not the stale notice was drawn on the way there — the batch is
        // running either way — and the bug being guarded against is entirely in the middle.
        val shown = mutableListOf<ImportState>()
        component.state.subscribe { shown += it.batch }

        imports.emit(ImportState.Picking)
        // The user picked before the notice was due: the batch is already running.
        imports.emit(ImportState.Running(index = 0, total = 1, name = "a.jpg", fileFraction = null))
        advanceTimeBy(401.milliseconds)

        assertTrue(
            shown.none { it is ImportState.Picking },
            "a superseded notice must be dropped, not merely delayed onto a running batch: $shown",
        )
        assertIs<ImportState.Running>(component.state.value.batch)
    }

    @Test
    fun a_running_batch_shows_as_importing_and_clears_when_it_ends() = runTest {
        val imports = FakeImports()
        val component = component(FakeVaultItems(), imports = imports)

        imports.emit(ImportState.Picking)
        assertTrue(component.state.value.importing, "the picker being up is part of importing")

        imports.emit(ImportState.Running(index = 0, total = 2, name = "a.jpg", fileFraction = 0.5f))
        assertTrue(component.state.value.importing)

        imports.emit(ImportState.Finished(imported = 2, failed = emptyList()))
        assertFalse(component.state.value.importing, "a finished batch is not importing")
        assertNull(component.state.value.error, "and a clean one raises no banner")
    }

    @Test
    fun a_batch_that_could_not_read_a_file_raises_a_dismissible_banner() = runTest {
        val imports = FakeImports()
        val component = component(FakeVaultItems(), imports = imports)

        imports.emit(
            ImportState.Finished(
                imported = 1,
                failed = listOf(FailedImport("broken.jpg", VaultError.Io(RuntimeException("unreadable")))),
            ),
        )

        assertEquals(GalleryError.ImportFailed, component.state.value.error)

        component.dismissError()

        assertNull(component.state.value.error, "the banner clears on acknowledgement")
        // And the summary is acknowledged at the source, so it cannot re-raise the banner the next
        // time the state re-emits.
        assertEquals(1, imports.acknowledged)
    }

    @Test
    fun a_cancelled_picker_leaves_no_error_behind() = runTest {
        val imports = FakeImports()
        val component = component(FakeVaultItems(), imports = imports)

        imports.emit(ImportState.Picking)
        imports.emit(ImportState.Idle) // an empty pick: the user backed out

        assertNull(component.state.value.error, "a cancelled picker is not a failure")
        assertFalse(component.state.value.importing)
    }

    /**
     * The component watches the batch; it does not own it (spec D7). A screen that is destroyed
     * mid-import — which the albums cycle makes ordinary — must not take fifty photos down with it.
     */
    @Test
    fun destroying_the_screen_does_not_end_the_batch() = runTest {
        val imports = FakeImports()
        component(FakeVaultItems(), imports = imports)

        imports.emit(ImportState.Running(index = 0, total = 50, name = "a.jpg", fileFraction = 0f))
        lifecycle.destroy()
        imports.emit(ImportState.Running(index = 1, total = 50, name = "b.jpg", fileFraction = 0f))

        assertEquals(0, imports.cancels, "destroying a screen must not cancel an import")
        assertTrue(imports.state.value is ImportState.Running, "the batch is still going")
    }

    @Test
    fun destroying_the_component_stops_its_work() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        lifecycle.destroy()

        vault.summaries.value = listOf(summary(byteArrayOf(9), readableMeta()))

        assertEquals(emptyList(), component.state.value.items, "a destroyed component stops collecting")
    }
}

/** A batch a test drives by hand: the component only watches this and forwards intents to it. */
private class FakeImports : VaultImports {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    override val state: StateFlow<ImportState> = _state

    var photoRequests = 0
        private set
    var fileRequests = 0
        private set
    var cancels = 0
        private set
    var acknowledged = 0
        private set
    var deleteRequests = 0
        private set
    var deleteConfirmations = 0
        private set
    var keeps = 0
        private set

    fun emit(next: ImportState) {
        _state.value = next
    }

    override fun importPhotos() {
        photoRequests++
    }

    override fun importFiles() {
        fileRequests++
    }

    override fun cancel() {
        cancels++
    }

    override fun acknowledge() {
        acknowledged++
    }

    override fun requestOriginalDelete() {
        deleteRequests++
    }

    override fun confirmOriginalDelete() {
        deleteConfirmations++
    }

    override fun keepOriginals() {
        keeps++
    }
}

/** A listing that fails the way a broken catalog does: by throwing inside the flow. */
private class ExplodingVaultItems : VaultItems {
    override fun observe(albumId: ByteArray?): Flow<List<VaultItemSummary>> =
        flow { throw IllegalStateException("catalog unreadable") }

    override fun import(
        name: String,
        mime: String,
        onProgress: (Long) -> Unit,
        isActive: () -> Boolean,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> = Outcome.Ok(byteArrayOf(1))

    override fun thumbnail(id: ByteArray): Outcome<ByteArray?> = Outcome.Ok(null)
}
