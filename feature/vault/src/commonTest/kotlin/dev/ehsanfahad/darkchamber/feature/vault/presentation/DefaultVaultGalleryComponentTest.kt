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
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.damagedMeta
import dev.ehsanfahad.darkchamber.feature.vault.domain.ImportFile
import dev.ehsanfahad.darkchamber.feature.vault.domain.ImportQueue
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    private fun component(
        vault: VaultItems,
        picker: FakeFilePicker = FakeFilePicker(FakePickedFile()),
    ): DefaultVaultGalleryComponent {
        val dispatcher = UnconfinedTestDispatcher()
        return DefaultVaultGalleryComponent(
            componentContext = DefaultComponentContext(lifecycle),
            observeGallery = ObserveGallery(vault),
            picker = picker,
            runImports = ImportQueue(ImportFile(vault = vault, ioContext = dispatcher)),
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
    fun importing_flags_the_state_and_clears_when_it_finishes() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)

        component.importFile()

        // The unconfined dispatcher runs the import to completion inline, so by here it is done.
        assertFalse(component.state.value.importing, "the importing flag clears when the import ends")
        assertNull(component.state.value.error)
        assertEquals(1, vault.importCalls)
    }

    @Test
    fun a_failed_import_surfaces_an_error_that_can_be_dismissed() = runTest {
        val vault = FakeVaultItems(importResult = Outcome.Err(VaultError.Io(RuntimeException("disk"))))
        val component = component(vault)

        component.importFile()

        assertEquals(GalleryError.ImportFailed, component.state.value.error)
        assertFalse(component.state.value.importing)

        component.dismissError()
        assertNull(component.state.value.error, "the banner clears on acknowledgement")
    }

    @Test
    fun every_picked_file_is_imported_in_the_order_it_was_picked() = runTest {
        val vault = FakeVaultItems()
        val picked = listOf(
            FakePickedFile(name = "one.jpg"),
            FakePickedFile(name = "two.jpg"),
            FakePickedFile(name = "three.jpg"),
        )
        val component = component(vault, picker = FakeFilePicker(picked))

        component.importFile()

        assertEquals(3, vault.importCalls, "a multi-select imports every file, not just the first")
        assertEquals(listOf("one.jpg", "two.jpg", "three.jpg"), vault.importedNames, "order is preserved")
        assertTrue(picked.all { it.closed }, "every grant is released, not only the last")
        assertNull(component.state.value.error)
        assertFalse(component.state.value.importing)
    }

    /** One unreadable file must not cost the user the other forty-nine. */
    @Test
    fun a_file_that_fails_does_not_stop_the_rest_of_the_batch() = runTest {
        val vault = FakeVaultItems(failFor = setOf("broken.jpg"))
        val picked = listOf(
            FakePickedFile(name = "good.jpg"),
            FakePickedFile(name = "broken.jpg"),
            FakePickedFile(name = "later.jpg"),
        )
        val component = component(vault, picker = FakeFilePicker(picked))

        component.importFile()

        assertEquals(listOf("good.jpg", "broken.jpg", "later.jpg"), vault.importedNames, "the batch continues")
        assertEquals(GalleryError.ImportFailed, component.state.value.error, "the failure is still reported")
        assertTrue(picked.all { it.closed }, "a failure mid-batch does not leak the remaining grants")
        assertFalse(component.state.value.importing)
    }

    @Test
    fun cancelling_the_picker_leaves_no_error_behind() = runTest {
        val vault = FakeVaultItems()
        // An empty list is how the seam says "the user backed out" — not an error, not a failure.
        val component = component(vault, picker = FakeFilePicker(emptyList()))

        component.importFile()

        assertNull(component.state.value.error, "a cancelled picker is not a failure")
        assertFalse(component.state.value.importing)
        assertEquals(0, vault.importCalls)
    }

    /** A picker that cannot even be shown is a failure, but there is no grant to release. */
    @Test
    fun a_picker_that_fails_to_open_is_reported_not_crashed() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault, picker = FakeFilePicker(failure = IllegalStateException("no activity")))

        component.importFile()

        assertEquals(GalleryError.ImportFailed, component.state.value.error)
        assertFalse(component.state.value.importing, "a picker failure still clears the flag")
        assertEquals(0, vault.importCalls)
    }

    /** Double-tapping the import button must not open two pickers or import twice. */
    @Test
    fun a_second_import_is_ignored_while_one_is_still_running() = runTest {
        val vault = FakeVaultItems()
        val picker = FakeFilePicker(FakePickedFile())
        // Queued, not inline: the first import is still pending when the second intent arrives.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val component = DefaultVaultGalleryComponent(
            componentContext = DefaultComponentContext(lifecycle),
            observeGallery = ObserveGallery(vault),
            picker = picker,
            runImports = ImportQueue(ImportFile(vault = vault, ioContext = dispatcher)),
            mainContext = dispatcher,
        )

        component.importFile()
        assertTrue(component.state.value.importing, "the flag is set immediately, not after the picker returns")
        component.importFile()

        advanceUntilIdle()

        assertEquals(1, picker.calls, "the second tap does not open a second picker")
        assertEquals(1, vault.importCalls)
        assertFalse(component.state.value.importing)
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
