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
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultVaultGalleryComponentTest {

    private val lifecycle = LifecycleRegistry().apply { resume() }

    private fun TestScope.component(
        vault: VaultItems,
        imports: VaultImports = FakeImports(),
    ): DefaultVaultGalleryComponent {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultVaultGalleryComponent(
            componentContext = DefaultComponentContext(lifecycle),
            observeGallery = ObserveGallery(vault),
            imports = imports,
            mainContext = dispatcher,
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
    fun the_import_intent_is_handed_to_the_batch_rather_than_run_here() = runTest {
        val imports = FakeImports()
        val component = component(FakeVaultItems(), imports = imports)

        component.importFile()

        // The component does not pick, loop or guard against a double tap: the batch outlives it,
        // so the batch is what knows whether one is already running.
        assertEquals(1, imports.fileRequests)
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
}
