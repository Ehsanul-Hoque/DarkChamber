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
import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpener
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultViewerComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerNotice
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.koin.core.module.Module
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
 * The host hands the viewer the platform's link opener from the graph and **its own lock** — the Lock
 * button's, not a stand-in — so a link a browser took ends the real session: the gate is told and the
 * key is gone. When nothing took it, or the graph has no opener, the session stays open.
 */
class ViewerLinkWiringTest {
    private val testDir = Path(SystemTemporaryDirectory, "vlnk-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val lifecycle = LifecycleRegistry().apply { resume() }
    private var gateTold = 0
    private val handed = mutableListOf<String>()
    private var answer = LinkOpened.Opened

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private val opener = object : LinkOpener {
        override suspend fun open(address: String): LinkOpened {
            handed += address
            return answer
        }
    }

    private fun graph(withOpener: Boolean): Module = module {
        single<CryptoEngine> { engine }
        single<FilePicker> { FakeFilePicker() }
        single<PhotoPicker> { FakePhotoPicker() }
        if (withOpener) single<LinkOpener> { opener }
    }

    private fun TestScope.component(open: VaultBootstrap.OpenVault, withOpener: Boolean = true): DefaultUnlockedComponent {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle, backHandler = BackDispatcher()),
            vault = open,
            koin = koinApplication { modules(graph(withOpener)) }.koin,
            mainContext = dispatcher,
            ioContext = dispatcher,
            mapContext = dispatcher,
            releaseContext = dispatcher,
            pdfContext = { dispatcher },
            notSavedRecord = NotSavedRecord(),
            onLock = { gateTold++ },
            onLeave = {},
        )
    }

    private val DefaultUnlockedComponent.gallery: VaultGalleryComponent
        get() = assertIs<UnlockedChild.Gallery>(stack.value.active.instance).component

    private val DefaultUnlockedComponent.viewer: VaultViewerComponent
        get() = assertIs<UnlockedChild.Viewer>(stack.value.active.instance).component

    /** The viewer open on a Markdown file, as a link is tapped from one. */
    private fun TestScope.viewerOnMarkdown(open: VaultBootstrap.OpenVault, withOpener: Boolean = true): Pair<DefaultUnlockedComponent, VaultViewerComponent> {
        val imported = vaultObjects(open).importObject(
            kind = VaultItemKind.FILE.code,
            name = "notes.md",
            mime = "text/markdown",
            openSource = { Buffer().apply { write("[site](https://example.com)\n".encodeToByteArray()) } },
        )
        val component = component(open, withOpener)
        component.gallery.tapItem((imported as Outcome.Ok).value.copyOf())
        component.viewer.viewportMeasured(1_000, 2_000)
        return component to component.viewer
    }

    @Test
    fun a_link_the_browser_took_locks_the_real_session() = runTest {
        val open = openVault()
        val (_, viewer) = viewerOnMarkdown(open)

        viewer.openLink("https://example.com")

        assertEquals(listOf("https://example.com"), handed, "the graph's opener had it")
        assertEquals(1, gateTold, "the gate was told, once")
        assertFalse(open.session.isOpen, "the key is gone: it was the subtree's own lock")
    }

    @Test
    fun a_link_nothing_took_leaves_the_session_open() = runTest {
        answer = LinkOpened.NotOpened
        val open = openVault()
        val (_, viewer) = viewerOnMarkdown(open)

        viewer.openLink("https://example.com")

        assertEquals(listOf("https://example.com"), handed, "the graph's opener was asked")
        assertEquals(0, gateTold)
        assertTrue(open.session.isOpen)
        assertEquals(ViewerNotice.LinkNotOpened, viewer.state.value.notice)
    }

    @Test
    fun without_an_opener_in_the_graph_no_link_opens_and_nothing_locks() = runTest {
        val open = openVault()
        val (_, viewer) = viewerOnMarkdown(open, withOpener = false)

        viewer.openLink("https://example.com")

        assertEquals(emptyList(), handed)
        assertEquals(0, gateTold)
        assertTrue(open.session.isOpen)
        assertEquals(ViewerNotice.LinkNotOpened, viewer.state.value.notice)
    }

    // ---- the vault -------------------------------------------------------------------------------

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
