package dev.ehsanfahad.darkchamber.app.root

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerDisplay
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayerFactory
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultViewerComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The host hands the viewer the platform's player and display from the graph, and a lock with a player
 * open completes without waiting for the player to let go — the release is held on a gate here, as a
 * decoder that blocks for seconds would hold it on a phone.
 */
class ViewerPlayerWiringTest {
    private val testDir = Path(SystemTemporaryDirectory, "vpw-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val players = RecordingPlayers()
    private val display = RecordingDisplay()
    private val release = GateDispatcher()
    private val lifecycle = LifecycleRegistry().apply { resume() }
    private var locked = false

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun graph(withPlayer: Boolean): Module = module {
        single<CryptoEngine> { engine }
        single<FilePicker> { FakeFilePicker() }
        single<PhotoPicker> { FakePhotoPicker() }
        if (withPlayer) {
            single<VaultPlayerFactory> { players }
            single<PlayerDisplay> { display }
        }
    }

    private fun TestScope.component(open: VaultBootstrap.OpenVault, withPlayer: Boolean = true): DefaultUnlockedComponent {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle, backHandler = BackDispatcher()),
            vault = open,
            koin = koinApplication { modules(graph(withPlayer)) }.koin,
            mainContext = dispatcher,
            ioContext = dispatcher,
            mapContext = dispatcher,
            releaseContext = release,
            notSavedRecord = NotSavedRecord(),
            onLock = { locked = true },
            onLeave = {},
        )
    }

    private val DefaultUnlockedComponent.gallery: VaultGalleryComponent
        get() = assertIs<UnlockedChild.Gallery>(stack.value.active.instance).component

    private val DefaultUnlockedComponent.viewer: VaultViewerComponent
        get() = assertIs<UnlockedChild.Viewer>(stack.value.active.instance).component

    private fun importClip(open: VaultBootstrap.OpenVault): ByteArray {
        val imported = vaultObjects(open).importObject(
            kind = VaultItemKind.FILE.code,
            name = "clip.mp4",
            mime = "video/mp4",
            openSource = { Buffer().apply { write(ByteArray(3_000) { it.toByte() }) } },
        )
        return (imported as Outcome.Ok).value
    }

    /** A lock as the gate does it: the teardown, then the subtree destroyed. */
    private fun DefaultUnlockedComponent.lockAsTheGateDoes() {
        lock()
        this@ViewerPlayerWiringTest.lifecycle.destroy()
    }

    @Test
    fun a_video_opens_in_the_graphs_player_over_its_own_reader() = runTest {
        val open = openVault()
        val clip = importClip(open)
        val component = component(open)

        component.gallery.tapItem(clip.copyOf())

        val player = players.made.single()
        assertEquals("video/mp4", player.mime)
        assertEquals(3_000L, player.reader.length, "the reader is the clip's own")
        assertEquals("play", player.calls.last())
        assertIs<ViewerPage.Media>(component.viewer.state.value.pages.values.single())
        assertEquals(ScreenOrientation.Landscape, component.viewer.state.value.orientationTarget, "the display is there")
    }

    @Test
    fun without_a_player_in_the_graph_a_video_shows_its_badge() = runTest {
        val open = openVault()
        val clip = importClip(open)
        val component = component(open, withPlayer = false)

        component.gallery.tapItem(clip.copyOf())
        component.viewer.viewportMeasured(1_000, 2_000)

        assertTrue(players.made.isEmpty())
        assertIs<ViewerPage.NotViewable>(component.viewer.state.value.pages.values.single(), "its badge, as before playback")
        assertEquals(null, component.viewer.state.value.orientationTarget, "and no orientation button")
    }

    @Test
    fun a_lock_with_a_player_open_completes_while_the_release_still_waits() = runTest {
        val open = openVault()
        val clip = importClip(open)
        val component = component(open)
        component.gallery.tapItem(clip.copyOf())
        val player = players.made.single()

        component.lockAsTheGateDoes()

        assertTrue(locked, "the gate was told")
        assertFalse(open.session.isOpen, "the key is gone")
        assertFalse(player.released, "the release has not run")
        assertEquals("pause", player.calls.last(), "but the player was silenced at once")
        assertTrue(release.pending > 0)

        release.runAll()
        assertTrue(player.released, "and it is let go off the lock's path")
        assertEquals(1, player.readerCloses, "closing the reader it owned, once")
    }

    @Test
    fun a_lock_puts_the_screen_back_as_it_was() = runTest {
        val open = openVault()
        val clip = importClip(open)
        val component = component(open)
        component.gallery.tapItem(clip.copyOf())
        component.viewer.switchOrientation()
        component.viewer.setBrightness(0.3f)
        players.made.single().state.value = PlayerState(playing = true)
        display.calls.clear()

        component.lockAsTheGateDoes()

        assertEquals(setOf("brightness:null", "orientation:AppDefault", "awake:false"), display.calls.toSet())
    }

    // ---- fakes -----------------------------------------------------------------------------------

    private class RecordingPlayer(val reader: RangeReader, val mime: String) : VaultPlayer {
        override val state = MutableStateFlow(PlayerState())
        val calls = mutableListOf<String>()
        var readerCloses = 0
        val released get() = "release" in calls

        override fun play() {
            calls += "play"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek:$positionMs"
        }

        override fun setSpeed(speed: Float) {
            calls += "speed:$speed"
        }

        override fun setVolume(level: Float) {
            calls += "volume:$level"
        }

        @Composable
        override fun Picture(modifier: Modifier) = Unit

        override fun release() {
            calls += "release"
            reader.close()
            readerCloses++
        }
    }

    private class RecordingPlayers : VaultPlayerFactory {
        val made = mutableListOf<RecordingPlayer>()

        override fun create(reader: RangeReader, mime: String): VaultPlayer = RecordingPlayer(reader, mime).also { made += it }
    }

    private class RecordingDisplay : PlayerDisplay {
        val calls = mutableListOf<String>()

        override fun brightness(): Float = 0.5f

        override fun setBrightness(level: Float?) {
            calls += "brightness:$level"
        }

        override fun setOrientation(orientation: ScreenOrientation) {
            calls += "orientation:$orientation"
        }

        override fun keepScreenOn(on: Boolean) {
            calls += "awake:$on"
        }
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
