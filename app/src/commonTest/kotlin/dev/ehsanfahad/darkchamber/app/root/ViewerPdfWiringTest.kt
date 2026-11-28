package dev.ehsanfahad.darkchamber.app.root

import androidx.compose.ui.graphics.ImageBitmap
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.app.FakeFilePicker
import dev.ehsanfahad.darkchamber.app.FakePhotoPicker
import dev.ehsanfahad.darkchamber.app.GateDispatcher
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
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPageSize
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfBuffer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfDocument
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfRenderer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultViewerComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The host hands the viewer the platform's PDF renderer from the graph and its own one-at-a-time
 * context for every call to an open PDF; a lock with a PDF open completes without waiting for the
 * document to close, which queues behind the PDF context — held on a gate here, as a page being drawn
 * would hold it on a phone.
 */
class ViewerPdfWiringTest {
    private val testDir = Path(SystemTemporaryDirectory, "vpdf-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val pdfs = RecordingPdfs()
    private val pdfContext = GateDispatcher()
    private val lifecycle = LifecycleRegistry().apply { resume() }
    private var locked = false
    private val content = ByteArray(5_000) { (it * 7).toByte() }

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun graph(withRenderer: Boolean): Module =
        module {
            single<CryptoEngine> { engine }
            single<FilePicker> { FakeFilePicker() }
            single<PhotoPicker> { FakePhotoPicker() }
            if (withRenderer) single<VaultPdfRenderer> { pdfs }
        }

    private fun TestScope.component(
        open: VaultBootstrap.OpenVault,
        withRenderer: Boolean = true,
    ): DefaultUnlockedComponent {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle, backHandler = BackDispatcher()),
            vault = open,
            koin = koinApplication { modules(graph(withRenderer)) }.koin,
            mainContext = dispatcher,
            ioContext = dispatcher,
            mapContext = dispatcher,
            releaseContext = dispatcher,
            pdfContext = { pdfContext },
            notSavedRecord = NotSavedRecord(),
            onLock = { locked = true },
            onLeave = {},
        )
    }

    private val DefaultUnlockedComponent.gallery: VaultGalleryComponent
        get() = assertIs<UnlockedChild.Gallery>(stack.value.active.instance).component

    private val DefaultUnlockedComponent.viewer: VaultViewerComponent
        get() = assertIs<UnlockedChild.Viewer>(stack.value.active.instance).component

    private fun importPdf(open: VaultBootstrap.OpenVault): ByteArray {
        val imported =
            vaultObjects(open).importObject(
                kind = VaultItemKind.FILE.code,
                name = "report.pdf",
                mime = "application/pdf",
                openSource = { Buffer().apply { write(content) } },
            )
        return (imported as Outcome.Ok).value
    }

    /** A lock as the gate does it: the teardown, then the subtree destroyed. */
    private fun DefaultUnlockedComponent.lockAsTheGateDoes() {
        lock()
        this@ViewerPdfWiringTest.lifecycle.destroy()
    }

    /** Tap the PDF and let the PDF context run the open. */
    private fun TestScope.openedPdf(): Pair<DefaultUnlockedComponent, RecordingBuffer> {
        val open = openVault()
        val pdf = importPdf(open)
        val component = component(open)
        component.gallery.tapItem(pdf.copyOf())
        component.viewer.viewportMeasured(1_000, 2_000)
        pdfContext.runAll()
        return component to pdfs.buffers.single()
    }

    @Test
    fun a_pdf_opens_in_the_graphs_renderer_on_the_hosts_pdf_context() =
        runTest {
            val open = openVault()
            val pdf = importPdf(open)
            val component = component(open)

            component.gallery.tapItem(pdf.copyOf())
            component.viewer.viewportMeasured(1_000, 2_000)

            val buffer = pdfs.buffers.single()
            assertEquals(content.size.toLong(), buffer.sizeHint, "sized from the item")
            assertFalse(buffer.opened, "the open waits for the host's PDF context")
            assertTrue(pdfContext.pending > 0)

            pdfContext.runAll()

            assertTrue(buffer.opened)
            assertContentEquals(content, buffer.bytes.readByteArray(), "the whole verified file reached the renderer")
            val page =
                assertIs<ViewerPage.Pdf>(
                    component.viewer.state.value.pages.values
                        .single(),
                )
            assertEquals(3, page.pageCount)
        }

    @Test
    fun without_a_renderer_in_the_graph_a_pdf_shows_its_badge() =
        runTest {
            val open = openVault()
            val pdf = importPdf(open)
            val component = component(open, withRenderer = false)

            component.gallery.tapItem(pdf.copyOf())
            component.viewer.viewportMeasured(1_000, 2_000)
            pdfContext.runAll()

            assertTrue(pdfs.buffers.isEmpty(), "nothing was read for a renderer")
            assertIs<ViewerPage.NotViewable>(
                component.viewer.state.value.pages.values
                    .single(),
                "its badge, as before PDFs",
            )
        }

    @Test
    fun a_lock_with_a_pdf_open_completes_while_the_close_still_waits() =
        runTest {
            val (component, buffer) = openedPdf()
            val document = buffer.document!!

            component.lockAsTheGateDoes()

            assertTrue(locked, "the gate was told")
            assertFalse(openedVault.session.isOpen, "the key is gone")
            assertEquals(0, document.closes, "the close has not run")
            assertTrue(pdfContext.pending > 0, "it is queued on the PDF context")

            pdfContext.runAll()
            assertEquals(1, document.closes, "and the document is closed, once")
        }

    /**
     * With the real vault and codec: a lock once the first 1 MiB chunk has reached the renderer's buffer
     * stops the decrypt there — the rest of the file is never decrypted, the buffer never opened.
     */
    @Test
    fun a_lock_during_the_read_stops_the_real_decrypt_within_a_chunk() =
        runTest {
            val open = openVault()
            val big = ByteArray(3 * MIB + 100) { (it * 31).toByte() }
            val imported =
                vaultObjects(open).importObject(
                    kind = VaultItemKind.FILE.code,
                    name = "scan.pdf",
                    mime = "application/pdf",
                    openSource = { Buffer().apply { write(big) } },
                )
            val component = component(open)
            var lockedAt = -1L
            pdfs.onWrite = { total ->
                if (lockedAt < 0 && total >= MIB) {
                    lockedAt = total
                    component.lockAsTheGateDoes()
                }
            }

            component.gallery.tapItem((imported as Outcome.Ok).value.copyOf())

            val buffer = pdfs.buffers.single()
            assertTrue(lockedAt >= MIB, "the lock came after the first chunk")
            assertTrue(
                buffer.bytes.size < 2L * MIB,
                "stopped within the next chunk: ${buffer.bytes.size} of ${big.size} bytes",
            )
            assertFalse(buffer.opened, "never opened")
            assertFalse(openedVault.session.isOpen)
        }

    // ---- fakes -----------------------------------------------------------------------------------

    private class RecordingPdfs : VaultPdfRenderer {
        val buffers = mutableListOf<RecordingBuffer>()
        override val supported = true

        /** Called after every write into a buffer, with how many bytes it holds. */
        var onWrite: (total: Long) -> Unit = {}

        override fun newBuffer(sizeHint: Long): VaultPdfBuffer =
            RecordingBuffer(sizeHint) { onWrite(it) }.also {
                buffers +=
                    it
            }
    }

    private class RecordingBuffer(
        val sizeHint: Long,
        private val onWrite: (Long) -> Unit = {},
    ) : VaultPdfBuffer {
        val bytes = Buffer()
        var opened = false
        var document: RecordingDocument? = null
        override val sink: RawSink =
            object : RawSink {
                override fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    bytes.write(source, byteCount)
                    onWrite(bytes.size)
                }

                override fun flush() = Unit

                override fun close() = Unit
            }

        override fun open(): PdfOpened {
            opened = true
            return PdfOpened.Document(RecordingDocument().also { document = it })
        }

        override fun close() = Unit
    }

    private class RecordingDocument : VaultPdfDocument {
        var closes = 0
        override val pageCount = 3

        override fun pageSize(index: Int) = PdfPageSize(595, 842)

        override fun render(
            index: Int,
            widthPx: Int,
        ): ImageBitmap? = null

        override fun close() {
            closes++
        }
    }

    // ---- the vault -------------------------------------------------------------------------------

    private companion object {
        const val MIB = 1024 * 1024
    }

    /** The vault the last [openVault] made, so a test can see its session close. */
    private lateinit var openedVault: VaultBootstrap.OpenVault

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
        return VaultBootstrap.OpenVault(session, VaultStorage(root), catalog).also { openedVault = it }
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
