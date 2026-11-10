package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.IdleVaultImports
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import dev.ehsanfahad.darkchamber.feature.vault.domain.CreateAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.DeleteAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.FileItems
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbums
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.domain.RenameAlbum
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Opening an item from the grid, and coming back to it: a tap opens the viewer only outside
 * selecting, carrying what the grid is showing; the way back brings the item last viewed into view.
 */
class GalleryOpenItemTest {
    private val lifecycle = LifecycleRegistry().apply { resume() }

    private class Opened(val id: List<Byte>, val order: GalleryOrder, val query: String)

    private val opened = mutableListOf<Opened>()

    private fun TestScope.component(vault: FakeVaultItems): DefaultVaultGalleryComponent {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultVaultGalleryComponent(
            componentContext = DefaultComponentContext(lifecycle, backHandler = BackDispatcher()),
            observeGallery = ObserveGallery(vault),
            imports = IdleVaultImports(),
            observeAlbum = ObserveAlbum(vault),
            renameAlbum = RenameAlbum(vault, ioContext = dispatcher),
            deleteAlbum = DeleteAlbum(vault, ioContext = dispatcher),
            observeAlbums = ObserveAlbums(vault),
            createAlbum = CreateAlbum(vault, ioContext = dispatcher),
            fileItems = FileItems(vault, ioContext = dispatcher),
            mainContext = dispatcher,
            progressInterval = Duration.ZERO,
            pickingDelay = Duration.ZERO,
            stageGrace = Duration.ZERO,
            onOpenItem = { id, order, query -> opened += Opened(id.toList(), order, query) },
        )
    }

    private fun FakeVaultItems.emit(vararg ids: Byte) {
        summaries.value = ids.map { summary(id = byteArrayOf(it), meta = readableMeta(name = "file-$it.jpg")) }
    }

    @Test
    fun a_tap_outside_selecting_opens_the_item_with_the_grids_order_and_search() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2, 3)
        component.chooseOrder(GalleryOrder.NameAsc)
        component.openSearch()
        component.editSearch("file")

        component.tapItem(byteArrayOf(2))

        val open = opened.single()
        assertEquals(listOf<Byte>(2), open.id)
        assertEquals(GalleryOrder.NameAsc, open.order)
        assertEquals("file", open.query)
        assertTrue(!component.state.value.selection.active, "a tap does not start selecting")
    }

    /** A search that was closed filters nothing, so the viewer is handed none. */
    @Test
    fun a_closed_search_hands_the_viewer_no_query() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2)
        component.openSearch()
        component.editSearch("file")
        component.closeSearch()

        component.tapItem(byteArrayOf(1))

        assertEquals("", opened.single().query)
    }

    @Test
    fun a_tap_while_selecting_toggles_and_opens_nothing() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2, 3)
        component.selectItem(byteArrayOf(1))

        component.tapItem(byteArrayOf(2))

        assertEquals(emptyList(), opened.map { it.id })
        assertEquals(2, component.state.value.selection.count)
    }

    @Test
    fun back_from_the_viewer_asks_once_for_the_item_to_be_brought_into_view() = runTest {
        val vault = FakeVaultItems()
        val component = component(vault)
        vault.emit(1, 2, 3)

        component.revealItem(byteArrayOf(2, 0x5A))

        assertEquals("025a", component.state.value.revealKey)

        component.revealHandled()

        assertNull(component.state.value.revealKey)
    }

    // ---- the scroll the screen makes ----------------------------------------------------------------

    private fun item(n: Int) = GalleryItem(
        id = byteArrayOf(n.toByte()),
        kind = VaultItemKind.FILE,
        size = 1L,
        createdAt = 1L,
        meta = GalleryItemMeta.Readable("f", "image/jpeg"),
    )

    private val items = (0 until 40).map(::item)

    @Test
    fun an_item_off_screen_is_scrolled_to() {
        assertEquals(30, revealScroll(items, byteArrayOf(30).toListKey(), visible = 0..11))
        assertEquals(2, revealScroll(items, byteArrayOf(2).toListKey(), visible = 12..23))
    }

    /** Already on screen: the grid stays where the user left it. */
    @Test
    fun an_item_already_on_screen_is_left_where_it_is() {
        assertNull(revealScroll(items, byteArrayOf(5).toListKey(), visible = 0..11))
        assertNull(revealScroll(items, byteArrayOf(0).toListKey(), visible = 0..11), "the first visible")
        assertNull(revealScroll(items, byteArrayOf(11).toListKey(), visible = 0..11), "the last visible")
    }

    /** Deleted from the viewer, or gone some other way: there is nothing to bring into view. */
    @Test
    fun an_item_no_longer_listed_moves_nothing() {
        assertNull(revealScroll(items, byteArrayOf(99).toListKey(), visible = 0..11))
    }
}
