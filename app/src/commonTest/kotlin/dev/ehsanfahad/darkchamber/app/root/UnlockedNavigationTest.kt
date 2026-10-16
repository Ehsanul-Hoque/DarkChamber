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
import dev.ehsanfahad.darkchamber.app.ui.crumbFor
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.label
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
import kotlin.test.assertNull
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

    /** How many times the subtree asked its host to leave the app. */
    private var leaves = 0

    private fun component(
        open: VaultBootstrap.OpenVault,
        mainContext: CoroutineContext = UnconfinedTestDispatcher(),
        // Where an album's write runs. Left as production's by default, because most of these tests
        // only navigate — but a test that *waits* for a write has to hand it a dispatcher the test
        // scheduler drives, or `advanceUntilIdle` returns with the work still in flight on a real
        // thread pool and the assertion reads the state from before it.
        ioContext: CoroutineContext = Dispatchers.Default,
    ): DefaultUnlockedComponent =
        DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(
                lifecycle = LifecycleRegistry().apply { resume() },
                backHandler = backDispatcher,
            ),
            vault = open,
            koin = koin,
            mainContext = mainContext,
            ioContext = ioContext,
            mapContext = mainContext,
            onLock = {},
            onLeave = { leaves++ },
        )

    @Test
    fun the_subtree_opens_on_the_item_grid() {
        val open = openVault()

        val stack = component(open).stack.value

        val child = assertIs<UnlockedChild.Gallery>(stack.active.instance, "unlock lands on the files")
        assertEquals(GalleryScope.WholeVault, child.scope, "and on the whole vault, not a filtered view of it")
        assertEquals(1, stack.items.size, "nothing is stacked on top of it")
    }

    @Test
    fun opening_albums_pushes_a_sibling_and_back_returns_to_the_grid() {
        val open = openVault()
        val component = component(open)

        component.openAlbums()

        assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)
        assertEquals(2, component.stack.value.items.size, "pushed on top of the grid, not in place of it")

        // Through the component's own entry point. The stack no longer handles back itself — the
        // navigation animation does, and calls this when the gesture finishes — so a press on the
        // host's dispatcher is answered in a composition, which a host test has none of. That the
        // press *reaches* this subtree is asserted in `RootComponentTest`, where the wiring lives.
        component.onBack()

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
        // Which listing, not merely "not the home one": the chrome above needs to tell an album from
        // Unfiled, and the boolean this replaced could not.
        assertEquals(GalleryScope.Album, child.scope, "an album's own grid, so it offers no albums entry")

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
        assertEquals(GalleryScope.Unfiled, child.scope, "the unfiled listing, so no second way into albums")
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
        assertEquals(GalleryScope.Album, child.scope, "and the album screen is still the one on top")
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

    /**
     * **Exactly one thing pops.**
     *
     * The animation that draws the transition takes its own `onBack` and calls it when the gesture
     * finishes, so the stack must not also handle back itself — both registered means one gesture
     * pops two screens. This is the half of that invariant a host test can reach: the pop goes
     * through the component's own entry point.
     */
    @Test
    fun one_back_pops_exactly_one_screen() {
        val component = component(openVault())
        component.openAlbums()
        assertEquals(2, component.stack.value.items.size)

        component.onBack()

        assertEquals(1, component.stack.value.items.size, "the grid, and nothing popped past it")
        assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)
    }

    /**
     * And the other half: **the stack does not register a back callback of its own.**
     *
     * A press reaching the host handler finds nothing here to answer it, because answering is the
     * animation's job and the animation only exists in a composition. If the stack were still
     * built with `handleBackButton = true` this would pop — and in production that pop would land
     * *as well as* the animation's, which is the double-pop this is written to catch.
     *
     * The previous cycle's whole back defect was a handler that silently went somewhere else, so
     * this drives the dispatcher the component was handed rather than trusting the flag.
     */
    @Test
    fun the_stack_leaves_back_to_the_animation() {
        val component = component(openVault())
        component.openAlbums()

        val handled = backDispatcher.back()

        assertTrue(!handled, "nothing in the component tree claims a plain back press")
        assertEquals(2, component.stack.value.items.size, "so nothing popped")
    }

    /**
     * **A selection still wins back, ahead of the offer to leave.**
     *
     * Three things want a back press inside the unlocked subtree and they have to answer in a fixed
     * order: a live selection first, then the screen below, then — with the grid as the only screen —
     * the offer to leave the app. The first two are settled by where their callbacks are registered:
     * the grid's lives in a child context, which Decompose gives a higher priority than the gate's
     * own. This is the pair that would be easiest to get wrong, because both are enabled at the same
     * moment: the grid is the only screen *and* something is selected.
     *
     * Here rather than beside the other leave tests because it needs an item to select, and this is
     * the file with the fixtures for that. What it proves is a priority between two callbacks on one
     * dispatcher, which does not depend on who built the dispatcher.
     */
    @Test
    fun a_press_while_selecting_ends_the_selection_rather_than_offering_to_leave() = runTest {
        val open = openVault(UnconfinedTestDispatcher(testScheduler))
        val component = component(open, UnconfinedTestDispatcher(testScheduler))
        val id = importInto(vaultObjects(open), null, "one.txt")
        advanceUntilIdle()
        val gallery = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance).component
        gallery.selectItem(id)
        assertTrue(gallery.state.value.selection.active, "something to come out of")

        val handled = backDispatcher.back()

        assertTrue(handled)
        assertTrue(!gallery.state.value.selection.active, "back left selecting mode")
        assertEquals(0, leaves, "and did not offer to leave, let alone leave")
        assertTrue(!component.leaveNotice.value, "nor put a notice on screen about it")
    }

    /**
     * What the bar says it is above, on each of the four screens.
     *
     * Built from real children rather than hand-made ones, because the answer that matters is the
     * album's — read from the live state, so it has to be a real component's.
     */
    @Test
    fun the_bar_names_the_listing_it_is_above() = runTest {
        val open = openVault(UnconfinedTestDispatcher(testScheduler))
        val component = component(open, UnconfinedTestDispatcher(testScheduler))
        val objects = vaultObjects(open)
        val albumId = (objects.createAlbum("Receipts") as Outcome.Ok).value

        val home = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)
        // The vault is the destination, so nothing is added beside its name.
        assertNull(crumbFor(home, home.component.state.value))

        component.openAlbums()
        val albums = assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)
        assertEquals("Albums", crumbFor(albums, null), "a sibling of the grid, so it says which it is")

        albums.component.openUnfiled()
        advanceUntilIdle()
        val unfiled = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)
        assertEquals("Unfiled", crumbFor(unfiled, unfiled.component.state.value))

        albums.component.openAlbum(albumId)
        advanceUntilIdle()
        val inAlbum = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)
        assertEquals("Receipts", crumbFor(inAlbum, inAlbum.component.state.value), "the album, by name")
    }

    /**
     * **A rename moves the crumb**, which is the reason it is read from live state rather than
     * copied out of the navigation config: the rename is made from this very screen, so a stale name
     * would be wrong in the one place the user is looking.
     */
    @Test
    fun renaming_an_album_renames_the_crumb() = runTest {
        val open = openVault(UnconfinedTestDispatcher(testScheduler))
        val component = component(
            open = open,
            mainContext = UnconfinedTestDispatcher(testScheduler),
            // The rename is a write, and this test waits for it.
            ioContext = UnconfinedTestDispatcher(testScheduler),
        )
        val albumId = (vaultObjects(open).createAlbum("Receipts") as Outcome.Ok).value
        component.openAlbums()
        val albums = assertIs<UnlockedChild.Albums>(component.stack.value.active.instance)
        albums.component.openAlbum(albumId)
        advanceUntilIdle()
        val child = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)
        assertEquals("Receipts", crumbFor(child, child.component.state.value))

        child.component.startAlbumRename()
        child.component.editAlbumName("Tax")
        child.component.confirmAlbumRename()
        advanceUntilIdle()

        assertEquals("Tax", crumbFor(child, child.component.state.value))
    }

    /**
     * An album whose name will not open gets the unverifiable form, never a blank and never a guess
     * — the same words the grid and the heading use, through the one helper that decides them.
     */
    @Test
    fun an_album_whose_name_will_not_open_still_has_a_crumb() = runTest {
        val open = openVault(UnconfinedTestDispatcher(testScheduler))
        val component = component(open, UnconfinedTestDispatcher(testScheduler))
        val child = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance)

        val crumb = crumbFor(
            child = UnlockedChild.Gallery(child.component, GalleryScope.Album),
            gallery = GalleryState(album = AlbumState.Present(AlbumTileName.Unreadable)),
        )

        assertEquals(AlbumTileName.Unreadable.label(), crumb)
    }

    /**
     * Two cases say nothing at all, and both are deliberate: an album that is **gone** must not be
     * named as though it were still there — the screen below is already explaining the exit — and a
     * name that has **not arrived yet** gets no placeholder for the user to watch be replaced.
     */
    @Test
    fun an_album_with_no_name_to_show_adds_nothing_to_the_bar() = runTest {
        val open = openVault(UnconfinedTestDispatcher(testScheduler))
        val component = component(open, UnconfinedTestDispatcher(testScheduler))
        val gallery = assertIs<UnlockedChild.Gallery>(component.stack.value.active.instance).component
        val child = UnlockedChild.Gallery(gallery, GalleryScope.Album)

        assertNull(crumbFor(child, GalleryState(album = AlbumState.Gone)), "gone, so not named")
        assertNull(crumbFor(child, GalleryState(album = null)), "not read yet, so nothing claimed")
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
