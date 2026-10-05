package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportStage
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Selecting, as the gallery exposes it: the mode, the count, and the two rules that are the
 * component's rather than the selection's own — long-press is refused while a batch is running, and
 * back leaves the mode instead of navigating.
 *
 * The selection's internals are tested directly in `ItemSelectionTest`; this is about the wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GallerySelectionTest {

    private val lifecycle = LifecycleRegistry().apply { resume() }
    private val backDispatcher = BackDispatcher()

    private fun TestScope.component(
        vault: FakeVaultItems,
        imports: VaultImports = StubImports(),
    ): DefaultVaultGalleryComponent =
        DefaultVaultGalleryComponent(
            componentContext = DefaultComponentContext(lifecycle, backHandler = backDispatcher),
            observeGallery = ObserveGallery(vault),
            imports = imports,
            mainContext = UnconfinedTestDispatcher(testScheduler),
            progressInterval = Duration.ZERO,
            pickingDelay = Duration.ZERO,
            stageGrace = Duration.ZERO,
        )

    private fun FakeVaultItems.emit(vararg ids: Byte) {
        summaries.value = ids.map { summary(id = byteArrayOf(it), meta = readableMeta()) }
    }

    @Test
    fun a_long_press_enters_selecting_mode_with_that_item_selected() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2, 3)

        component.selectItem(byteArrayOf(2))

        val selection = component.state.value.selection
        assertTrue(selection.active)
        assertEquals(1, selection.count)
        assertTrue(selection.contains(byteArrayOf(2)), "by content — the tile asks with its own array")
        assertFalse(selection.contains(byteArrayOf(1)))
    }

    @Test
    fun the_count_follows_the_selection() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2, 3)

        component.selectItem(byteArrayOf(1))
        component.toggleItem(byteArrayOf(2))
        assertEquals(2, component.state.value.selection.count)

        component.toggleItem(byteArrayOf(1))
        assertEquals(1, component.state.value.selection.count)
    }

    @Test
    fun select_all_takes_everything_the_screen_is_showing() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2, 3)
        component.selectItem(byteArrayOf(1))

        component.selectAllItems()

        assertEquals(3, component.state.value.selection.count)
    }

    @Test
    fun clearing_leaves_the_mode() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2)
        component.selectItem(byteArrayOf(1))

        component.clearSelection()

        assertFalse(component.state.value.selection.active)
        assertEquals(0, component.state.value.selection.count)
    }

    /**
     * Back leaves the mode rather than navigating, and **only** while selecting — otherwise back
     * belongs to whoever is doing the navigating, and swallowing it here would trap the user on this
     * screen.
     */
    @Test
    fun back_exits_selecting_mode_and_is_otherwise_not_handled() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2)

        assertFalse(backDispatcher.isEnabled, "nothing selected: back is not ours")

        component.selectItem(byteArrayOf(1))
        assertTrue(backDispatcher.isEnabled, "selecting: back is ours")

        assertTrue(backDispatcher.back(), "and it was handled here")
        assertFalse(component.state.value.selection.active, "which left the mode")
        assertFalse(backDispatcher.isEnabled, "and handed back back")
    }

    /**
     * The one rule beyond the selection itself: the grid grows underneath an import, so a selection
     * made then is a selection of a moving target — and the bottom area is already showing the
     * progress card.
     */
    @Test
    fun a_long_press_during_a_running_batch_does_nothing() = runTest {
        val vault = FakeVaultItems()
        val imports = StubImports()
        val component = component(vault, imports)
        vault.emit(1, 2)
        imports.emit(
            ImportState.Running(index = 0, total = 2, name = "a.jpg", stage = ImportStage.ENCRYPTING, fileFraction = 0.5f),
        )

        component.selectItem(byteArrayOf(1))

        assertFalse(component.state.value.selection.active, "no mode")
        assertEquals(0, component.state.value.selection.count)
        assertFalse(backDispatcher.isEnabled, "and back was never taken over")
    }

    /** A pick in flight counts as a batch: items can land the moment it returns. */
    @Test
    fun a_long_press_while_a_pick_is_in_flight_does_nothing() = runTest {
        val vault = FakeVaultItems()
        val imports = StubImports()
        val component = component(vault, imports)
        vault.emit(1)
        imports.emit(ImportState.Picking)

        component.selectItem(byteArrayOf(1))

        assertFalse(component.state.value.selection.active)
    }

    /** A finished batch is not a running one — the summary is on screen and selecting is fine again. */
    @Test
    fun a_long_press_after_a_batch_finishes_works() = runTest {
        val vault = FakeVaultItems()
        val imports = StubImports()
        val component = component(vault, imports)
        vault.emit(1)
        imports.emit(ImportState.Finished(imported = 1, failed = emptyList()))

        component.selectItem(byteArrayOf(1))

        assertTrue(component.state.value.selection.active)
    }

    /**
     * The live-list intersection, through the component: an item shredded while selected leaves the
     * selection, so the count cannot overstate what an action would touch.
     */
    @Test
    fun an_item_that_leaves_the_listing_leaves_the_selection() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2)
        component.selectItem(byteArrayOf(1))
        component.toggleItem(byteArrayOf(2))
        assertEquals(2, component.state.value.selection.count)

        vault.emit(2)

        assertEquals(1, component.state.value.selection.count)
        assertFalse(component.state.value.selection.contains(byteArrayOf(1)))
        assertTrue(component.state.value.selection.active, "the mode survives — the user did not ask to leave")
    }

    @Test
    fun a_tap_outside_selecting_mode_selects_nothing() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1)

        component.toggleItem(byteArrayOf(1))

        assertFalse(component.state.value.selection.active, "a tap is not a long-press")
    }

    /** The state's own equality has to see a selection change, or the screen will not redraw. */
    @Test
    fun selecting_changes_the_published_state() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2)
        val before = component.state.value

        component.selectItem(byteArrayOf(1))

        assertFalse(before == component.state.value, "a selection is a state change")
    }
}

/** A batch that only reports what it is told to, so a test can park the gallery in any state. */
private class StubImports : VaultImports {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    override val state: StateFlow<ImportState> = _state

    fun emit(next: ImportState) {
        _state.value = next
    }

    override fun importPhotos() = Unit

    override fun importFiles() = Unit

    override fun cancel() = Unit

    override fun acknowledge() = Unit

    override fun requestOriginalDelete() = Unit

    override fun confirmOriginalDelete() = Unit

    override fun keepOriginals() = Unit
}
