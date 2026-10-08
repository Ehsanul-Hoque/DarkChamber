package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.app.FakeFilePicker
import dev.ehsanfahad.darkchamber.app.FakePhotoPicker
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.testSqlDriver
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The albums destination inside the unlocked subtree.
 *
 * The album grid is a **sibling** of the item grid, not a new root: unlocking still lands on the
 * user's files, and the gate's structure — exactly two children, the unlocked one constructed with a
 * live session — is load-bearing for the security design, so this adds to the stack rather than
 * reshaping it.
 *
 * The thing most worth pinning is the config carrying an album id as **hex**. Configs are compared
 * for equality and serialized for state restoration, and `ByteArray` breaks both — two configs for
 * the same album would never be equal, and the array would not round-trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockedNavigationTest {
    private val testDir = Path(SystemTemporaryDirectory, "unt-${Random.nextULong()}")

    private val koin: Koin = koinApplication {
        modules(
            module {
                single<CryptoEngine> { CryptographyKotlinEngine() }
                single<FilePicker> { FakeFilePicker() }
                single<PhotoPicker> { FakePhotoPicker() }
            },
        )
    }.koin

    private val backDispatcher = BackDispatcher()

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun component(
        open: VaultBootstrap.OpenVault,
        mainContext: CoroutineContext = UnconfinedTestDispatcher(),
    ): DefaultUnlockedComponent =
        DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(
                lifecycle = LifecycleRegistry().apply { resume() },
                backHandler = backDispatcher,
            ),
            vault = open,
            koin = koin,
            mainContext = mainContext,
            mapContext = mainContext,
            onLock = {},
        )

    @Test
    fun the_subtree_opens_on_the_item_grid() {
        val open = openVault()

        val stack = component(open).stack.value

        val child = assertIs<UnlockedChild.Gallery>(stack.active.instance, "unlock lands on the files")
        assertTrue(child.atHome, "and on the whole vault rather than a filtered view of it")
        assertEquals(1, stack.items.size, "nothing is stacked on top of it")
    }

    @Test
    fun opening_albums_pushes_a_sibling_and_back_returns_to_the_grid() {
        val open = openVault()
        val component = component(open)

        component.openAlbums()

        assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)
        assertEquals(2, component.stack.value.items.size, "pushed on top of the grid, not in place of it")

        assertTrue(backDispatcher.back(), "the stack handles back")
        assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance, "back returns to the files")
        assertEquals(1, component.stack.value.items.size)
    }

    /**
     * The album id survives the round trip through a config. This is the test the hex decision exists
     * for: the id goes in as bytes, is carried as a string, and has to come back out as the same
     * bytes for the filtered listing to show the right album.
     */
    /**
     * The album id survives the round trip through a config, asserted **on what the pushed screen
     * lists** rather than on the catalog.
     *
     * An earlier version of this test read the catalog directly, which proved the album had one item
     * in it and nothing at all about the filter reaching the component — the mutation that hardcoded
     * the filter to "everything" passed it.
     */
    @Test
    fun opening_an_album_pushes_a_gallery_filtered_to_that_album() = runTest {
        // One scheduler for the catalog's mapping and the component's own work, driven by this test:
        // a listing's first emission then arrives when the test says so rather than whenever a
        // background thread gets to it.
        val open = openVault(UnconfinedTestDispatcher(testScheduler))
        val component = component(open, UnconfinedTestDispatcher(testScheduler))
        val objects = vaultObjects(open)
        val albumId = (objects.createAlbum("Receipts") as Outcome.Ok).value
        val filed = importInto(objects, albumId, "filed.txt")
        importInto(objects, null, "unfiled.txt")
        component.openAlbums()
        val albums = assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)

        albums.component.openAlbum(albumId)
        advanceUntilIdle()

        val child = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)
        assertEquals(3, component.stack.value.items.size, "grid, albums, then the filtered grid")
        assertTrue(!child.atHome, "a filtered gallery is not the home one, so it offers no albums entry")

        val listed = child.component.state.value.items
        assertEquals(1, listed.size, "the filtered screen shows the album, not the vault")
        assertTrue(listed.single().id.contentEquals(filed), "and the id came back out of the config intact")
    }

    /**
     * Unfiled is reachable, which is the whole reason the filter became three-state: `album_id = ?`
     * cannot express "no album", so before this there was no way to look at the items nobody has
     * filed — which is every item imported before albums existed.
     */
    @Test
    fun opening_unfiled_pushes_a_gallery_showing_only_the_items_in_no_album() = runTest {
        val open = openVault(UnconfinedTestDispatcher(testScheduler))
        val component = component(open, UnconfinedTestDispatcher(testScheduler))
        val objects = vaultObjects(open)
        val albumId = (objects.createAlbum("Receipts") as Outcome.Ok).value
        importInto(objects, albumId, "filed.txt")
        val loose = importInto(objects, null, "loose.txt")
        component.openAlbums()
        val albums = assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)

        albums.component.openUnfiled()
        advanceUntilIdle()

        val child = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)
        assertTrue(!child.atHome, "a filtered gallery, so it offers no second way into albums")
        val listed = child.component.state.value.items
        assertEquals(1, listed.size, "only what is in no album")
        assertTrue(listed.single().id.contentEquals(loose))
        assertEquals(
            GalleryScope.Unfiled,
            child.component.state.value.scope,
            "and it knows which listing it is, so an empty one can say the right thing",
        )
    }

    @Test
    fun opening_unfiled_twice_is_ignored_rather_than_fatal() = runTest {
        val open = openVault(UnconfinedTestDispatcher(testScheduler))
        val component = component(open, UnconfinedTestDispatcher(testScheduler))
        component.openAlbums()
        val albums = assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)

        albums.component.openUnfiled()
        albums.component.openUnfiled()

        assertEquals(3, component.stack.value.items.size, "grid, albums, unfiled — and no duplicate")
    }

    /** Each listing publishes which one it is, because the empty-state copy depends on it. */
    @Test
    fun the_scope_a_gallery_publishes_matches_the_filter_it_was_built_with() = runTest {
        val open = openVault(UnconfinedTestDispatcher(testScheduler))
        val component = component(open, UnconfinedTestDispatcher(testScheduler))
        val albumId = (vaultObjects(open).createAlbum("Receipts") as Outcome.Ok).value

        val home = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)
        assertEquals(GalleryScope.WholeVault, home.component.state.value.scope)

        component.openAlbums()
        val albums = assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)
        albums.component.openAlbum(albumId)
        val inAlbum = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)

        assertEquals(GalleryScope.Album, inAlbum.component.state.value.scope)
    }

    @Test
    fun the_home_gallery_still_lists_the_whole_vault() = runTest {
        val open = openVault(UnconfinedTestDispatcher(testScheduler))
        val objects = vaultObjects(open)
        val albumId = (objects.createAlbum("Receipts") as Outcome.Ok).value
        importInto(objects, albumId, "filed.txt")
        importInto(objects, null, "unfiled.txt")

        val child = assertIs<UnlockedChild.Gallery>(
            component(open, UnconfinedTestDispatcher(testScheduler)).stack.value.active.instance,
        )
        advanceUntilIdle()

        assertEquals(2, child.component.state.value.items.size, "filed and unfiled alike")
    }

    @Test
    fun two_albums_are_two_different_destinations() {
        val open = openVault()
        val component = component(open)
        val objects = vaultObjects(open)
        val first = (objects.createAlbum("First") as Outcome.Ok).value
        val second = (objects.createAlbum("Second") as Outcome.Ok).value
        component.openAlbums()
        val albums = assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)

        albums.component.openAlbum(first)
        albums.component.openAlbum(second)

        assertEquals(
            4,
            component.stack.value.items.size,
            "the second album is its own screen — configs for different albums must not compare equal",
        )
    }

    /**
     * **A double tap must not crash.** A stack may not hold the same configuration twice and
     * Decompose throws when asked to, so two taps on an album tile before the first screen is on top
     * — one finger, no timing trick — would have taken the app down.
     *
     * Found while checking what the hex decision actually buys: two configs for the same album now
     * compare *equal*, which is the point of carrying hex, and that is exactly what makes the second
     * push a duplicate rather than a harmless extra screen.
     */
    @Test
    fun opening_the_same_album_twice_is_ignored_rather_than_fatal() {
        val open = openVault()
        val component = component(open)
        val album = (vaultObjects(open).createAlbum("One") as Outcome.Ok).value
        component.openAlbums()
        val albums = assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)
        albums.component.openAlbum(album)
        val depth = component.stack.value.items.size

        albums.component.openAlbum(album)

        assertEquals(depth, component.stack.value.items.size, "the second tap added nothing and threw nothing")
        val child = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)
        assertTrue(!child.atHome, "and the album screen is still the one on top")
    }

    /** The same hazard on the albums entry itself, which is also a button a finger can hit twice. */
    @Test
    fun opening_albums_twice_is_ignored_rather_than_fatal() {
        val open = openVault()
        val component = component(open)

        component.openAlbums()
        component.openAlbums()

        assertEquals(2, component.stack.value.items.size)
        assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)
    }

    /** One small item, filed or not, so a listing has something to be right or wrong about. */
    private fun importInto(objects: VaultObjects, albumId: ByteArray?, name: String): ByteArray =
        (
            objects.importObject(
                kind = VaultItemKind.FILE.code,
                name = name,
                mime = "text/plain",
                albumId = albumId,
                chunkSize = 64,
                openSource = { Buffer().apply { write(byteArrayOf(1, 2, 3)) } },
            ) as Outcome.Ok
            ).value

    private fun vaultObjects(open: VaultBootstrap.OpenVault): VaultObjects {
        val engine = koin.get<CryptoEngine>()
        return VaultObjects(
            session = open.session,
            storage = open.storage,
            catalog = open.catalog,
            codec = dev.ehsanfahad.darkchamber.core.crypto.DchmCodec(engine),
            clock = { 1_000L },
            random = engine::randomBytes,
        )
    }

    private fun openVault(catalogContext: CoroutineDispatcher = Dispatchers.Default): VaultBootstrap.OpenVault {
        val engine = CryptographyKotlinEngine()
        val cheap = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)
        val session: VaultSession =
            VaultSession.createVault(engine, DefaultKeyDerivation(engine), "pw".encodeToByteArray(), cheap).session
        val root = Path(testDir, "vault-${Random.nextULong()}")
        SystemFileSystem.createDirectories(Path(root, "tmp"))
        // The catalog's reactive mapping runs on [catalogContext]. A test that reads a listing
        // passes one sharing its own scheduler: with the default dispatcher the first emission
        // arrives on a background thread whenever it gets there, which such a test either misses or
        // — worse — happens to catch because it did something slow beforehand.
        val catalog = (CatalogStore.create(testSqlDriver(), catalogContext) as Outcome.Ok).value
        return VaultBootstrap.OpenVault(session, VaultStorage(root), catalog)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
