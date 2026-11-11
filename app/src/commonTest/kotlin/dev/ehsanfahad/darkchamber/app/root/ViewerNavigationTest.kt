package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.app.FakeFilePicker
import dev.ehsanfahad.darkchamber.app.FakePhotoPicker
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.testSqlDriver
import dev.ehsanfahad.darkchamber.app.ui.crumbFor
import dev.ehsanfahad.darkchamber.app.ui.paddedByHost
import dev.ehsanfahad.darkchamber.app.ui.showsUnlockedBar
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultViewerComponent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The viewer inside the unlocked subtree: opened by a tap on a tile, on that grid's own listing, in
 * its order and under its search; left through back, handing the grid the item last viewed.
 *
 * Built from the place that builds the real children — the unlocked component — with a real vault
 * and a test-owned back dispatcher, because what is pinned here is the host's wiring.
 */
class ViewerNavigationTest {
    private val testDir = Path(SystemTemporaryDirectory, "vnt-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()

    private val koin = koinApplication {
        modules(
            module {
                single<CryptoEngine> { engine }
                single<FilePicker> { FakeFilePicker() }
                single<PhotoPicker> { FakePhotoPicker() }
            },
        )
    }.koin

    private val lifecycle = LifecycleRegistry().apply { resume() }

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun TestScope.component(open: VaultBootstrap.OpenVault): DefaultUnlockedComponent {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle, backHandler = BackDispatcher()),
            vault = open,
            koin = koin,
            mainContext = dispatcher,
            ioContext = dispatcher,
            mapContext = dispatcher,
            notSavedRecord = NotSavedRecord(),
            onLock = {},
            onLeave = {},
        )
    }

    private val DefaultUnlockedComponent.active get() = stack.value.active.instance

    private val DefaultUnlockedComponent.gallery: VaultGalleryComponent
        get() = assertIs<UnlockedChild.Gallery>(active).component

    private val DefaultUnlockedComponent.viewer: VaultViewerComponent
        get() = assertIs<UnlockedChild.Viewer>(active).component

    private fun VaultViewerComponent.shownId() = state.value.items[state.value.index].id.toList()

    /** Three files with names that sort differently from the order they were added in. */
    private fun importThree(objects: VaultObjects): List<ByteArray> =
        listOf("cherry.jpg", "apple.jpg", "banana.jpg").map { name ->
            val imported = objects.importObject(
                kind = VaultItemKind.FILE.code,
                name = name,
                mime = "image/jpeg",
                openSource = { Buffer().apply { write(byteArrayOf(1, 2, 3)) } },
            )
            (imported as Outcome.Ok).value
        }

    @Test
    fun a_tap_on_a_tile_opens_the_viewer_on_that_item() = runTest {
        val open = openVault()
        val (_, apple, _) = importThree(vaultObjects(open))
        val component = component(open)

        component.gallery.tapItem(apple.copyOf())

        assertEquals(apple.toList(), component.viewer.shownId())
    }

    /** Two taps before the first push lands are one viewer, not a duplicate-config crash. */
    @Test
    fun two_taps_on_a_tile_open_one_viewer() = runTest {
        val open = openVault()
        val (_, apple, _) = importThree(vaultObjects(open))
        val component = component(open)
        val gallery = component.gallery

        gallery.tapItem(apple.copyOf())
        gallery.tapItem(apple.copyOf())

        assertEquals(2, component.stack.value.items.size)
    }

    /** While selecting, a tap is a toggle: nothing is opened. */
    @Test
    fun a_tap_while_selecting_opens_nothing() = runTest {
        val open = openVault()
        val (cherry, apple, _) = importThree(vaultObjects(open))
        val component = component(open)

        component.gallery.selectItem(cherry.copyOf())
        component.gallery.tapItem(apple.copyOf())

        assertIs<UnlockedChild.Gallery>(component.active)
        assertEquals(2, component.gallery.state.value.selection.count)
    }

    /** The viewer swipes through what the grid was showing: its order and its search, handed over in memory. */
    @Test
    fun the_viewer_shows_the_grids_order_and_search() = runTest {
        val open = openVault()
        val (cherry, apple, banana) = importThree(vaultObjects(open))
        val component = component(open)
        val gallery = component.gallery
        gallery.chooseOrder(GalleryOrder.NameDesc)
        gallery.openSearch()
        gallery.editSearch("an") // banana only… and nothing else has "an"
        gallery.closeSearch()
        gallery.openSearch()
        gallery.editSearch("a") // apple, banana — not cherry

        gallery.tapItem(apple.copyOf())

        assertEquals(listOf(banana.toList(), apple.toList()), component.viewer.state.value.items.map { it.id.toList() })
        assertFalse(cherry.toList() in component.viewer.state.value.items.map { it.id.toList() })
    }

    /** Back pops the viewer and hands the grid the item last viewed — the one swiped to, not the one tapped. */
    @Test
    fun back_returns_to_the_grid_with_the_item_last_viewed() = runTest {
        val open = openVault()
        val (cherry, apple, banana) = importThree(vaultObjects(open))
        val component = component(open)
        val gallery = component.gallery
        gallery.chooseOrder(GalleryOrder.NameAsc) // apple, banana, cherry

        gallery.tapItem(apple.copyOf())
        component.viewer.showPage(2) // cherry
        component.onBack()

        assertIs<UnlockedChild.Gallery>(component.active)
        assertEquals(cherry.toHexForTest(), component.gallery.state.value.revealKey)
        assertFalse(banana.toHexForTest() == component.gallery.state.value.revealKey)
    }

    /** The viewer's own back button leaves the same way. */
    @Test
    fun the_viewers_back_button_returns_the_same_way() = runTest {
        val open = openVault()
        val (_, apple, _) = importThree(vaultObjects(open))
        val component = component(open)

        component.gallery.tapItem(apple.copyOf())
        component.viewer.back()

        assertIs<UnlockedChild.Gallery>(component.active)
        assertEquals(apple.toHexForTest(), component.gallery.state.value.revealKey)
    }

    /** Opened from inside an album, the viewer swipes through that album. */
    @Test
    fun opened_from_an_album_the_viewer_lists_that_album() = runTest {
        val open = openVault()
        val objects = vaultObjects(open)
        val (cherry, apple, _) = importThree(objects)
        val trip = (objects.createAlbum("Trip") as Outcome.Ok).value
        objects.assignItems(listOf(cherry.copyOf(), apple.copyOf()), trip.copyOf())
        val component = component(open)

        component.openAlbums()
        assertIs<UnlockedChild.Albums>(component.active).component.openAlbum(trip.copyOf())
        component.gallery.tapItem(apple.copyOf())

        assertEquals(setOf(cherry.toList(), apple.toList()), component.viewer.state.value.items.map { it.id.toList() }.toSet())
    }

    /** Like the camera: full-screen over the vault's bar, and not padded by the host. */
    @Test
    fun the_viewer_is_drawn_full_screen_without_the_vaults_bar() = runTest {
        val open = openVault()
        val (_, apple, _) = importThree(vaultObjects(open))
        val component = component(open)

        component.gallery.tapItem(apple.copyOf())

        assertFalse(showsUnlockedBar(component.active))
        assertFalse(paddedByHost(component.active))
        assertNull(crumbFor(component.active, gallery = null))
    }

    private fun ByteArray.toHexForTest(): String = joinToString("") { b ->
        val v = b.toInt() and 0xFF
        "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
    }

    private fun vaultObjects(open: VaultBootstrap.OpenVault): VaultObjects =
        VaultObjects(
            session = open.session,
            storage = open.storage,
            catalog = open.catalog,
            codec = DchmCodec(engine),
            clock = { 1_000L },
            random = engine::randomBytes,
        )

    private fun TestScope.openVault(): VaultBootstrap.OpenVault {
        val cheap = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)
        val session = VaultSession.createVault(engine, DefaultKeyDerivation(engine), "pw".encodeToByteArray(), cheap).session
        val root = Path(testDir, "vault-${Random.nextULong()}")
        SystemFileSystem.createDirectories(Path(root, "tmp"))
        val catalog = (CatalogStore.create(testSqlDriver(), UnconfinedTestDispatcher(testScheduler)) as Outcome.Ok).value
        return VaultBootstrap.OpenVault(session, VaultStorage(root), catalog)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
