package dev.ehsanfahad.darkchamber.feature.camera.presentation

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureProblem
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureQueueState
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationInfo
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The camera screen's decisions, each a function of state — so that with no UI tests, what the
 * screen shows is still asserted somewhere.
 */
class CameraStateTest {

    private fun state(
        permission: CameraPermission = CameraPermission.Granted,
        availability: CameraAvailability = CameraAvailability.Available,
        holding: Int = 0,
        problem: CaptureProblem? = null,
        destination: DestinationInfo? = DestinationInfo(DestinationTitle.AllItems, 10L),
        capabilities: CameraCapabilities = CameraCapabilities(setOf(Lens.Back, Lens.Front), true, 1f, 8f),
    ) = CameraState(
        destination = destination,
        permission = permission,
        availability = availability,
        capabilities = capabilities,
        queue = CaptureQueueState(holding = holding, bound = 3, problem = problem),
    )

    @Test
    fun the_shutter_works_only_with_permission_a_camera_room_in_the_queue_and_a_destination() {
        assertTrue(state().shutterEnabled)

        // Every other permission, with everything else fine.
        for (p in CameraPermission.entries - CameraPermission.Granted) {
            assertFalse(state(permission = p).shutterEnabled, "permission $p")
        }
        // Every other availability, with permission granted.
        for (a in CameraAvailability.entries - CameraAvailability.Available) {
            assertFalse(state(availability = a).shutterEnabled, "availability $a")
        }
        assertFalse(state(holding = 3).shutterEnabled, "the queue is full")
        assertTrue(state(holding = 2).shutterEnabled, "one place left")
        assertFalse(
            state(destination = DestinationInfo(DestinationTitle.AlbumGone, null)).shutterEnabled,
            "every photo would fail once the album is gone",
        )
    }

    /**
     * A destination that has not loaded yet does not block the shutter: the import checks the album
     * itself, so a photo taken in that first moment is filed or fails honestly — it is never lost.
     */
    @Test
    fun a_destination_still_loading_does_not_block_the_shutter() {
        assertTrue(state(destination = null).shutterEnabled)
    }

    @Test
    fun which_explainer_shows_for_each_permission_and_availability() {
        assertEquals(CameraExplainer.None, state(permission = CameraPermission.NotAsked).explainer, "the system prompt is up")
        assertEquals(CameraExplainer.Denied, state(permission = CameraPermission.Denied).explainer)
        assertEquals(CameraExplainer.PermanentlyDenied, state(permission = CameraPermission.PermanentlyDenied).explainer)
        assertEquals(CameraExplainer.None, state().explainer)
        assertEquals(CameraExplainer.InUse, state(availability = CameraAvailability.InUseByAnotherApp).explainer)
        assertEquals(CameraExplainer.Unavailable, state(availability = CameraAvailability.Unavailable).explainer)
    }

    /** Availability means nothing until the camera may be opened, so permission speaks first. */
    @Test
    fun a_permission_problem_is_explained_before_an_availability_one() {
        val both = state(permission = CameraPermission.Denied, availability = CameraAvailability.Unavailable)
        assertEquals(CameraExplainer.Denied, both.explainer)
    }

    @Test
    fun a_count_is_shown_only_once_it_is_known() {
        assertEquals(10L, state().count)
        assertNull(state(destination = null).count, "not loaded yet is not zero")
        assertNull(state(destination = DestinationInfo(DestinationTitle.AllItems, null)).count, "unreadable is not zero")
        assertEquals(0L, state(destination = DestinationInfo(DestinationTitle.Unfiled, 0L)).count, "a real zero is shown")
    }

    /** The album being gone outranks a single failed photo: it explains every photo that follows. */
    @Test
    fun the_message_line_says_the_album_is_gone_before_any_single_failure() {
        assertNull(state().message)
        assertEquals(CameraMessage.SaveFailed, state(problem = CaptureProblem.SaveFailed).message)
        assertEquals(CameraMessage.OutOfSpace, state(problem = CaptureProblem.OutOfSpace).message)
        assertEquals(CameraMessage.CaptureFailed, state(problem = CaptureProblem.CaptureFailed).message)
        assertEquals(
            CameraMessage.AlbumGone,
            state(problem = CaptureProblem.SaveFailed, destination = DestinationInfo(DestinationTitle.AlbumGone, null)).message,
        )
    }

    @Test
    fun flip_and_flash_are_offered_only_where_the_hardware_has_them() {
        assertTrue(state().flipOffered)
        assertTrue(state().flashOffered)
        val single = CameraCapabilities(setOf(Lens.Back), hasFlash = false, minZoom = 1f, maxZoom = 1f)
        assertFalse(state(capabilities = single).flipOffered, "one lens: nothing to flip to")
        assertFalse(state(capabilities = single).flashOffered, "no flash: no flash control")
    }
}
