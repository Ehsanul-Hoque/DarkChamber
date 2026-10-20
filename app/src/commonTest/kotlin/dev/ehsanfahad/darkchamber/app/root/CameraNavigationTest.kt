package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.app.FakeCameraController
import dev.ehsanfahad.darkchamber.app.FakeFilePicker
import dev.ehsanfahad.darkchamber.app.FakePhotoPicker
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.testSqlDriver
import dev.ehsanfahad.darkchamber.app.ui.crumbFor
import dev.ehsanfahad.darkchamber.app.ui.showsUnlockedBar
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The camera inside the unlocked subtree: opened from a gallery on that gallery's own listing,
 * closed back to it, and torn down with everything else on lock.
 *
 * Built from the place that builds the real contexts — the unlocked component — with a real vault
 * and a test-owned back dispatcher, because what is pinned here is the host's wiring: which filter
 * the camera is handed, how it is pushed, and **which scope its saves run in**. Only this component
 * can get that last one wrong, and only a save that outlives the screen shows it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraNavigationTest {
    private val testDir = Path(SystemTemporaryDirectory, "cnt-${Random.nextULong()}")
    private val camera = FakeCameraController()
    private val engine = CryptographyKotlinEngine()

    private val koin = koinApplication {
        modules(
            module {
                single<CryptoEngine> { engine }
                single<FilePicker> { FakeFilePicker() }
                single<PhotoPicker> { FakePhotoPicker() }
                single<CameraController> { camera }
            },
        )
    }.koin

    private val lifecycle = LifecycleRegistry().apply { resume() }
    private var locks = 0

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun TestScope.component(
        open: VaultBootstrap.OpenVault,
        graph: Koin = koin,
    ): DefaultUnlockedComponent {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle, backHandler = BackDispatcher()),
            vault = open,
            koin = graph,
            mainContext = dispatcher,
            ioContext = dispatcher,
            mapContext = dispatcher,
            onLock = { locks++ },
            onLeave = {},
        )
    }

    private val DefaultUnlockedComponent.active get() = stack.value.active.instance

    private fun DefaultUnlockedComponent.openCameraFromTop() =
        assertIs<UnlockedChild.Gallery>(active).component.openCamera()

    /** Each listing hands the camera itself as the destination, album id intact. */
    @Test
    fun each_listing_opens_the_camera_on_itself() = runTest {
        val open = openVault()
        val objects = vaultObjects(open)
        val trip = (objects.createAlbum("Trip") as Outcome.Ok).value
        val component = component(open)

        component.openCameraFromTop()
        assertEquals(CameraDestination.AllItems, assertIs<UnlockedChild.Camera>(component.active).component.destination)
        component.onBack()

        component.openAlbums()
        assertIs<UnlockedChild.Albums>(component.active).component.openUnfiled()
        component.openCameraFromTop()
        assertEquals(CameraDestination.Unfiled, assertIs<UnlockedChild.Camera>(component.active).component.destination)
        component.onBack()
        component.onBack()

        assertIs<UnlockedChild.Albums>(component.active).component.openAlbum(trip.copyOf())
        component.openCameraFromTop()
        val inAlbum = assertIs<UnlockedChild.Camera>(component.active).component
        assertEquals(CameraDestination.Album(trip), inAlbum.destination)

        // And the photo really goes there — the destination is not only reported but used.
        inAlbum.shoot()
        assertEquals(1L, objects.observeItemCount(ItemFilter.Album(trip)).first())
    }

    /**
     * The camera is drawn without the vault's bar — its own ✕ is the way out, and the bar's lock
     * button and crumb would sit on top of a viewfinder. Every other screen keeps it.
     */
    @Test
    fun only_the_camera_hides_the_vault_bar() = runTest {
        val component = component(openVault())
        assertTrue(showsUnlockedBar(component.active), "the grid")

        component.openCameraFromTop()
        assertFalse(showsUnlockedBar(component.active), "the camera")
        assertEquals(null, crumbFor(component.active, gallery = null), "and names nothing in a bar it has not got")

        component.onBack()
        component.openAlbums()
        assertTrue(showsUnlockedBar(component.active), "the album grid")
    }

    /**
     * A platform module that binds no camera opens a camera screen that says it is unavailable —
     * not a crash on the gallery's button.
     */
    @Test
    fun no_bound_camera_still_opens_the_screen() = runTest {
        val bare = koinApplication {
            modules(
                module {
                    single<CryptoEngine> { engine }
                    single<FilePicker> { FakeFilePicker() }
                    single<PhotoPicker> { FakePhotoPicker() }
                },
            )
        }.koin
        val component = component(openVault(), graph = bare)

        component.openCameraFromTop()

        assertIs<UnlockedChild.Camera>(component.active)
    }

    /** A double tap on the button is one camera, not two — and not a crash on a duplicate config. */
    @Test
    fun a_double_tap_opens_one_camera() = runTest {
        val component = component(openVault())

        component.openCameraFromTop()
        // The second tap lands on the gallery's component, which is still alive beneath.
        assertIs<UnlockedChild.Gallery>(component.stack.value.items.first().instance).component.openCamera()

        assertEquals(2, component.stack.value.items.size, "the grid and one camera")
    }

    /** ✕ goes back to the listing the camera was opened from, and releases the camera. */
    @Test
    fun closing_returns_to_the_same_listing() = runTest {
        val component = component(openVault())
        component.openAlbums()
        assertIs<UnlockedChild.Albums>(component.active).component.openUnfiled()
        component.openCameraFromTop()
        assertTrue(camera.isOpen)

        assertIs<UnlockedChild.Camera>(component.active).component.close()

        assertEquals(GalleryScope.Unfiled, assertIs<UnlockedChild.Gallery>(component.active).scope)
        assertEquals(3, component.stack.value.items.size, "one screen popped, no more")
        assertFalse(camera.isOpen, "the camera is released with its screen")
    }

    /** A lock — here, backgrounding — tears the camera down with the subtree and releases it. */
    @Test
    fun a_lock_releases_the_camera() = runTest {
        val component = component(openVault())
        component.openCameraFromTop()
        assertTrue(camera.isOpen)

        lifecycle.destroy()

        assertEquals(1, locks, "it locked")
        assertFalse(camera.isOpen, "and the camera indicator is off")
    }

    /**
     * Closing the camera with a photo still on its way is **not** a lock: the photo was taken on
     * purpose and is saved. This holds only if the host builds the camera's saves in the session's
     * scope rather than the screen's.
     */
    @Test
    fun closing_with_a_save_in_flight_lets_it_finish() = runTest {
        val open = openVault()
        val objects = vaultObjects(open)
        val component = component(open)
        camera.holdCaptures()
        component.openCameraFromTop()
        assertIs<UnlockedChild.Camera>(component.active).component.shoot()

        assertIs<UnlockedChild.Camera>(component.active).component.close()
        assertIs<UnlockedChild.Gallery>(component.active)
        camera.release()

        assertEquals(1L, objects.observeItemCount(ItemFilter.All).first(), "the photo is in the vault")
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

    private fun TestScope.openVault(): VaultBootstrap.OpenVault = openVault(UnconfinedTestDispatcher(testScheduler))

    private fun openVault(catalogContext: CoroutineDispatcher): VaultBootstrap.OpenVault {
        val cheap = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)
        val session = VaultSession.createVault(engine, DefaultKeyDerivation(engine), "pw".encodeToByteArray(), cheap).session
        val root = Path(testDir, "vault-${Random.nextULong()}")
        SystemFileSystem.createDirectories(Path(root, "tmp"))
        val catalog = (CatalogStore.create(testSqlDriver(), catalogContext) as Outcome.Ok).value
        return VaultBootstrap.OpenVault(session, VaultStorage(root), catalog)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
