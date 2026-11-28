package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
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
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.TextKind
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultViewerComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A text file opened from the gallery through the real vault and codec: the host's viewer reads it,
 * decrypted and verified, and shows it as text — formatted where it is Markdown — and a lock lets it go.
 */
class ViewerTextWiringTest {
    private val testDir = Path(SystemTemporaryDirectory, "vtxt-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val lifecycle = LifecycleRegistry().apply { resume() }
    private val markdown = "# Notes\n\nA *quiet* line.\n"

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun TestScope.component(open: VaultBootstrap.OpenVault): DefaultUnlockedComponent {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle, backHandler = BackDispatcher()),
            vault = open,
            koin =
                koinApplication {
                    modules(
                        module {
                            single<CryptoEngine> { engine }
                            single<FilePicker> { FakeFilePicker() }
                            single<PhotoPicker> { FakePhotoPicker() }
                        },
                    )
                }.koin,
            mainContext = dispatcher,
            ioContext = dispatcher,
            mapContext = dispatcher,
            releaseContext = dispatcher,
            pdfContext = { dispatcher },
            notSavedRecord = NotSavedRecord(),
            onLock = {},
            onLeave = {},
        )
    }

    private val DefaultUnlockedComponent.gallery: VaultGalleryComponent
        get() = assertIs<UnlockedChild.Gallery>(stack.value.active.instance).component

    private val DefaultUnlockedComponent.viewer: VaultViewerComponent
        get() = assertIs<UnlockedChild.Viewer>(stack.value.active.instance).component

    /** Stored as an unknown type, as some providers record a Markdown file: the name is what says so. */
    private fun importMarkdown(open: VaultBootstrap.OpenVault): ByteArray {
        val imported =
            vaultObjects(open).importObject(
                kind = VaultItemKind.FILE.code,
                name = "notes.md",
                mime = "application/octet-stream",
                openSource = { Buffer().apply { write(markdown.encodeToByteArray()) } },
            )
        return (imported as Outcome.Ok).value
    }

    @Test
    fun a_markdown_file_opens_as_formatted_text_through_the_real_vault() =
        runTest {
            val open = openVault()
            val id = importMarkdown(open)
            val component = component(open)

            component.gallery.tapItem(id.copyOf())
            component.viewer.viewportMeasured(1_000, 2_000)

            assertEquals(
                ViewerPage.Text(TextKind.Markdown),
                component.viewer.state.value.pages.values
                    .single(),
            )
            // Outside the feature its contents are out of reach; what it prints says enough.
            assertEquals(
                "TextDocument(3 lines, formatted=true)",
                component.viewer.texts.value.values
                    .single()
                    .toString(),
            )
        }

    @Test
    fun a_lock_lets_the_text_go() =
        runTest {
            val open = openVault()
            val id = importMarkdown(open)
            val component = component(open)
            component.gallery.tapItem(id.copyOf())
            component.viewer.viewportMeasured(1_000, 2_000)
            val viewer = component.viewer

            component.lock()
            lifecycle.destroy()

            assertTrue(viewer.texts.value.isEmpty())
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
        val session =
            VaultSession
                .createVault(
                    engine,
                    DefaultKeyDerivation(engine),
                    "pw".encodeToByteArray(),
                    cheap,
                ).session
        val root = Path(testDir, "vault-${Random.nextULong()}")
        SystemFileSystem.createDirectories(Path(root, "tmp"))
        val catalog =
            (
                CatalogStore.create(
                    testSqlDriver(),
                    UnconfinedTestDispatcher(testScheduler),
                ) as Outcome.Ok
            ).value
        return VaultBootstrap.OpenVault(session, VaultStorage(root), catalog)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
