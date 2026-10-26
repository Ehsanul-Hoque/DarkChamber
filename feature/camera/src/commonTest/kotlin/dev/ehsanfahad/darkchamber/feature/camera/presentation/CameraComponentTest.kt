package dev.ehsanfahad.darkchamber.feature.camera.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.stop
import dev.ehsanfahad.darkchamber.feature.camera.FakeCameraController
import dev.ehsanfahad.darkchamber.feature.camera.FakeCaptureVault
import dev.ehsanfahad.darkchamber.feature.camera.FakeClipFiles
import dev.ehsanfahad.darkchamber.feature.camera.FakeFreeSpace
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraSession
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.cameraComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The camera component over a fake camera and a fake vault: when the camera is open, what the
 * controls reach, and what closing and destroying leave behind.
 *
 * Every dispatcher shares the test's scheduler, and captures are held until the test releases them,
 * so nothing here passes on elapsed time. The save path through a real vault is
 * [CameraVaultTest]'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraComponentTest {

    private inner class Harness(
        test: TestScope,
        val camera: FakeCameraController = FakeCameraController(),
        destination: CameraDestination = CameraDestination.AllItems,
    ) {
        val lifecycle = LifecycleRegistry()
        val vault = FakeCaptureVault()
        val sessionJob = Job()
        var closes = 0
        private val dispatcher = UnconfinedTestDispatcher(test.testScheduler)
        val session = CameraSession(
            vault = vault,
            clips = FakeClipFiles(),
            freeSpace = FakeFreeSpace(),
            saveScope = CoroutineScope(dispatcher + sessionJob),
            notSaved = { _, _, _ -> },
            ioContext = dispatcher,
            clock = { 0L },
        )
        val component: CameraComponent = cameraComponent(
            componentContext = DefaultComponentContext(lifecycle),
            session = session,
            camera = camera,
            destination = destination,
            unreadableNameLabel = "Name can't be read",
            onClose = { closes++ },
            mainContext = dispatcher,
        )
        val state: CameraState get() = component.state.value
    }

    /**
     * The camera is open exactly while the screen is started — so the platform's in-use indicator
     * goes off when the screen stops, and again when a lock destroys it from the foreground.
     */
    @Test
    fun the_camera_is_open_only_while_the_screen_is_started() = runTest {
        val h = Harness(this)
        assertFalse(h.camera.isOpen, "nothing opens before the screen starts")

        h.lifecycle.resume()
        assertTrue(h.camera.isOpen, "a started screen opens the camera")

        h.lifecycle.stop()
        assertFalse(h.camera.isOpen, "a stopped one releases it")
        h.camera.permission.value = CameraPermission.Denied
        h.camera.permission.value = CameraPermission.Granted
        assertFalse(h.camera.isOpen, "and a grant arriving while it is stopped does not reopen it")

        h.lifecycle.resume()
        assertTrue(h.camera.isOpen, "and coming back opens it again")

        h.lifecycle.destroy()
        assertFalse(h.camera.isOpen, "a destroyed one releases it too")
        assertEquals(2, h.camera.starts)
        h.sessionJob.cancel()
    }

    /**
     * Permission is asked for on the first entry to the camera, not at app start — and only once: a
     * screen coming back from a stop must not raise the system prompt again over an answer given.
     * The grant opens the camera without the screen having to restart.
     */
    @Test
    fun a_first_entry_asks_once_and_a_grant_opens_the_camera() = runTest {
        val camera = FakeCameraController(permission = CameraPermission.NotAsked).apply {
            permissionAnswer = CameraPermission.Granted
        }
        val h = Harness(this, camera)

        h.lifecycle.resume()
        assertEquals(1, camera.permissionRequests, "the first start asks")
        assertTrue(camera.isOpen, "and the grant opens the camera")
        assertEquals(CameraPermission.Granted, h.state.permission, "which the screen sees")

        h.lifecycle.stop()
        camera.permission.value = CameraPermission.NotAsked
        h.lifecycle.resume()
        assertEquals(1, camera.permissionRequests, "a later start does not ask again")
        h.sessionJob.cancel()
    }

    /**
     * Access turned on in the system's settings is not announced to the app: the platform is only
     * asked again when the camera starts. A screen that waits for "granted" before starting would
     * keep saying access is off over a camera it may now open.
     */
    @Test
    fun access_turned_on_in_settings_opens_the_camera_on_the_next_start() = runTest {
        val camera = FakeCameraController(permission = CameraPermission.PermanentlyDenied)
        val h = Harness(this, camera)
        h.lifecycle.resume()
        assertFalse(camera.isOpen)

        h.lifecycle.stop()
        camera.platformPermission = CameraPermission.Granted
        h.lifecycle.resume()

        assertTrue(camera.isOpen, "the next start finds the camera allowed and opens it")
        assertEquals(CameraPermission.Granted, h.state.permission, "and the screen stops saying it is off")
        h.sessionJob.cancel()
    }

    /** Only "Allow" asks again once the first answer was no. */
    @Test
    fun allow_asks_again() = runTest {
        val camera = FakeCameraController(permission = CameraPermission.Denied)
        val h = Harness(this, camera)
        h.lifecycle.resume()
        assertEquals(0, camera.permissionRequests, "a refusal is not re-asked unprompted")

        h.component.requestPermission()
        assertEquals(1, camera.permissionRequests)
        assertTrue(camera.isOpen)

        h.component.openSettings()
        assertEquals(1, camera.settingsOpened)
        h.sessionJob.cancel()
    }

    /**
     * A press reaches the camera only when the shutter is shown enabled — the screen draws from the
     * same state, so a press it lets through on a disabled shutter would be a photo nobody was
     * offered.
     */
    @Test
    fun the_shutter_takes_a_photo_only_when_it_is_enabled() = runTest {
        val camera = FakeCameraController(permission = CameraPermission.Denied)
        val h = Harness(this, camera, CameraDestination.Album(byteArrayOf(7)))
        h.vault.albums.value = listOf(
            AlbumSummary(id = byteArrayOf(7), name = Outcome.Ok("Trip"), itemCount = 0, coverItemId = null, createdAt = 1L),
        )
        h.lifecycle.resume()

        h.component.shoot()
        assertEquals(0, camera.captures, "no permission, no photo")

        camera.permission.value = CameraPermission.Granted
        h.component.shoot()
        assertEquals(1, camera.captures, "granted, the press captures")
        assertEquals(1, h.state.queue.saved, "and the photo is saved")
        assertContentEquals(byteArrayOf(7), h.vault.imported.single().albumId, "into the album it came from")
        assertNotNull(h.state.queue.lastSavedId, "which the tile is drawn from")

        h.vault.albums.value = emptyList()
        h.component.shoot()
        assertEquals(1, camera.captures, "an album that is gone takes nothing more")
        h.sessionJob.cancel()
    }

    @Test
    fun flip_switches_lens_and_is_ignored_with_only_one() = runTest {
        val h = Harness(this)
        h.lifecycle.resume()

        h.component.flipLens()
        assertEquals(Lens.Front, h.camera.lens)
        assertEquals(Lens.Front, h.state.lens, "the control draws the lens in use")
        h.component.flipLens()
        assertEquals(Lens.Back, h.camera.lens, "and flips back")

        h.camera.capabilities.value = CameraCapabilities(setOf(Lens.Back), hasFlash = true, minZoom = 1f, maxZoom = 8f)
        h.component.flipLens()
        assertEquals(Lens.Back, h.state.lens, "one lens, nothing to flip to")
        h.sessionJob.cancel()
    }

    @Test
    fun flash_cycles_off_auto_on_and_is_ignored_without_one() = runTest {
        val h = Harness(this)
        h.lifecycle.resume()

        val seen = List(3) { h.component.cycleFlash(); h.camera.flash to h.state.flash }
        assertEquals(
            listOf(FlashMode.Auto to FlashMode.Auto, FlashMode.On to FlashMode.On, FlashMode.Off to FlashMode.Off),
            seen,
        )

        h.camera.capabilities.value = CameraCapabilities(setOf(Lens.Front), hasFlash = false, minZoom = 1f, maxZoom = 1f)
        h.component.cycleFlash()
        assertEquals(FlashMode.Off, h.state.flash, "a lens without a flash offers no setting to step")
        h.sessionJob.cancel()
    }

    /** Zoom stays inside the range the camera reports; a focus point stays inside the viewfinder. */
    /**
     * The platform's camera outlives the screen — one for the app — so what a previous visit set is
     * still set there. A new screen starts at the back lens with the flash off and says so; the
     * camera has to agree, or "Flash: off" is drawn over a flash that fires.
     */
    @Test
    fun a_new_screen_puts_the_camera_back_to_its_own_settings() = runTest {
        val camera = FakeCameraController().apply {
            setLens(Lens.Front)
            setFlash(FlashMode.On)
            setZoom(4f)
            setMode(CaptureMode.Video)
            setQuality(VideoQuality.UHD)
            setTorch(true)
        }
        val h = Harness(this, camera)
        h.lifecycle.resume()

        assertEquals(Lens.Back to Lens.Back, camera.lens to h.state.lens)
        assertEquals(FlashMode.Off to FlashMode.Off, camera.flash to h.state.flash)
        assertEquals(1f to 1f, camera.zoom to h.state.zoom)
        assertEquals(CaptureMode.Photo to CaptureMode.Photo, camera.mode to h.state.mode)
        assertEquals(VideoQuality.HD to VideoQuality.HD, camera.quality to h.state.quality, "the session's quality")
        assertEquals(false to false, camera.torch to h.state.torch)
        h.sessionJob.cancel()
    }

    @Test
    fun zoom_and_focus_are_kept_in_range() = runTest {
        val h = Harness(this)
        h.lifecycle.resume()

        h.component.zoomTo(20f)
        assertEquals(8f, h.state.zoom, "clamped to the camera's widest")
        h.component.zoomTo(0.2f)
        assertEquals(1f, h.state.zoom, "and to its narrowest")
        assertEquals(1f, h.camera.zoom)

        h.component.focusAt(-0.1f, 1.4f)
        assertEquals(listOf(0f to 1f), h.camera.focusPoints)
        h.sessionJob.cancel()
    }

    /** A double tap on ✕ reports once — a second report would pop the gallery underneath too. */
    @Test
    fun close_reports_once() = runTest {
        val h = Harness(this)
        h.lifecycle.resume()

        h.component.close()
        h.component.close()

        assertEquals(1, h.closes)
        h.sessionJob.cancel()
    }

    /**
     * Switching to video, or flipping the lens, rebinds the camera, and a rebind under a photo still
     * in the platform's hands fails it. So the switch waits for the photos already pressed, then
     * happens: a quick thumb never loses a shot to the control beside the shutter.
     */
    @Test
    fun a_mode_switch_or_flip_waits_for_the_photos_in_flight() = runTest {
        val h = Harness(this)
        h.camera.holdCaptures()
        h.lifecycle.resume()
        h.component.shoot()
        h.component.shoot()

        h.component.setMode(CaptureMode.Video)
        h.component.flipLens()
        assertEquals(CaptureMode.Photo, h.camera.mode, "not while a photo is being taken")
        assertEquals(Lens.Back, h.camera.lens)
        assertEquals(CaptureMode.Photo, h.state.mode, "and the screen still shows where the photo is coming from")

        h.camera.release()
        assertEquals(CaptureMode.Photo, h.camera.mode, "one photo is still on its way")
        h.camera.release()

        assertEquals(2, h.vault.imported.size, "both photos are kept")
        assertEquals(CaptureMode.Video, h.camera.mode)
        assertEquals(Lens.Front, h.camera.lens)
        assertEquals(CaptureMode.Video, h.state.mode)
        h.sessionJob.cancel()
    }

    /** What happens once the photos land is what was asked for last: the latest mode, and a flip only if one is still wanted. */
    @Test
    fun the_last_switch_asked_for_while_waiting_is_the_one_made() = runTest {
        val h = Harness(this)
        h.camera.holdCaptures()
        h.lifecycle.resume()
        h.component.shoot()

        h.component.setMode(CaptureMode.Video)
        h.component.setMode(CaptureMode.Photo)
        h.component.flipLens()
        h.component.flipLens()
        h.camera.release()

        assertEquals(CaptureMode.Photo, h.camera.mode, "switched back before the photo landed")
        assertEquals(Lens.Back, h.camera.lens, "flipped and flipped back")
        assertEquals(1, h.vault.imported.size)
        h.sessionJob.cancel()
    }

    /**
     * Destroying the screen stops it following anything, but a photo already taken is still saved:
     * the save runs in the session's scope, which only a lock cancels.
     */
    @Test
    fun a_destroyed_screen_stops_following_but_its_photo_still_saves() = runTest {
        val h = Harness(this)
        h.camera.holdCaptures()
        h.lifecycle.resume()
        h.component.shoot()
        assertEquals(1, h.state.queue.holding)

        h.lifecycle.destroy()
        h.camera.permission.value = CameraPermission.Denied
        assertEquals(CameraPermission.Granted, h.state.permission, "the destroyed screen follows nothing")

        h.camera.release()
        assertEquals(1, h.vault.imported.size, "the photo taken before closing is in the vault")
        assertTrue(h.sessionJob.children.none { it.isActive }, "and nothing of the camera is left running")
        h.sessionJob.cancel()
    }
}
