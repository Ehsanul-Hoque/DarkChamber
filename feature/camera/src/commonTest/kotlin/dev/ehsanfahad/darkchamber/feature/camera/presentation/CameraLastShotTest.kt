package dev.ehsanfahad.darkchamber.feature.camera.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.feature.camera.FakeCameraController
import dev.ehsanfahad.darkchamber.feature.camera.FakeCaptureVault
import dev.ehsanfahad.darkchamber.feature.camera.FakeClipFiles
import dev.ehsanfahad.darkchamber.feature.camera.FakeFreeSpace
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraSession
import dev.ehsanfahad.darkchamber.feature.camera.cameraComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The last-shot tile's tap: it reports the newest capture's id to the host, and only when opening would
 * not cost a capture — the rule itself is [LastShotOpensTest]'s; this is the wiring.
 */
class CameraLastShotTest {
    private val opened = mutableListOf<List<Byte>>()

    private inner class Harness(test: TestScope) {
        val camera = FakeCameraController()
        val vault = FakeCaptureVault()
        val sessionJob = Job()
        private val dispatcher = UnconfinedTestDispatcher(test.testScheduler)
        val lifecycle = LifecycleRegistry()
        val component = cameraComponent(
            componentContext = DefaultComponentContext(lifecycle),
            session = CameraSession(
                vault = vault,
                clips = FakeClipFiles(),
                freeSpace = FakeFreeSpace(),
                saveScope = CoroutineScope(dispatcher + sessionJob),
                notSaved = { _, _, _ -> },
                ioContext = dispatcher,
                clock = { 0L },
            ),
            camera = camera,
            destination = CameraDestination.AllItems,
            unreadableNameLabel = "Name can't be read",
            onClose = {},
            mainContext = dispatcher,
            onOpenLastShot = { opened += it.toList() },
        )
        val state: CameraState get() = component.state.value
    }

    @Test
    fun with_nothing_taken_yet_a_tap_opens_nothing() = runTest {
        val h = Harness(this)
        h.lifecycle.resume()

        h.component.openLastShot()

        assertEquals(emptyList(), opened)
        h.sessionJob.cancel()
    }

    @Test
    fun a_tap_opens_the_newest_capture() = runTest {
        val h = Harness(this)
        h.lifecycle.resume()
        h.component.shoot()
        val shot = assertNotNull(h.state.lastShot).itemId.toList()

        h.component.openLastShot()

        assertEquals(listOf(shot), opened)
        h.sessionJob.cancel()
    }

    /** A second photo is in the platform's hands: covering the camera now would fail it. */
    @Test
    fun a_tap_while_a_photo_is_capturing_opens_nothing_until_it_lands() = runTest {
        val h = Harness(this)
        h.lifecycle.resume()
        h.component.shoot()
        h.camera.holdCaptures()
        h.component.shoot()

        h.component.openLastShot()
        assertEquals(emptyList(), opened)

        h.camera.release()
        h.component.openLastShot()
        assertEquals(listOf(assertNotNull(h.state.lastShot).itemId.toList()), opened)
        h.sessionJob.cancel()
    }
}
