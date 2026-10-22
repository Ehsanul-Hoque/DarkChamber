package dev.ehsanfahad.darkchamber.feature.camera.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.ehsanfahad.darkchamber.core.designsystem.FlashGlyph
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureQueueState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The choices the camera screen makes that are not already [CameraState]'s — pulled out of the
 * layout because no UI test can see inside one.
 */
class CameraScreenDecisionsTest {

    private fun state(permission: CameraPermission, availability: CameraAvailability) = CameraState(
        destination = null,
        permission = permission,
        availability = availability,
        capabilities = CameraCapabilities.None,
        queue = CaptureQueueState(holding = 0, bound = 3),
    )

    /**
     * The viewfinder is drawn exactly when no explainer stands in its place — never both, so an
     * explainer is never read over a live preview, and never neither while the camera can show
     * something. Not asked yet draws the (empty) viewfinder: the system prompt is up over it.
     */
    @Test
    fun the_viewfinder_shows_exactly_when_no_explainer_does() {
        for (permission in CameraPermission.entries) {
            for (availability in CameraAvailability.entries) {
                val s = state(permission, availability)
                assertEquals(
                    s.explainer == CameraExplainer.None,
                    s.showsViewfinder,
                    "$permission / $availability",
                )
            }
        }
        assertEquals(true, state(CameraPermission.NotAsked, CameraAvailability.Unavailable).showsViewfinder)
        assertEquals(false, state(CameraPermission.Granted, CameraAvailability.InUseByAnotherApp).showsViewfinder)
    }

    /** A flash drawn as off while it fires would be invisible to anything that renders one mark. */
    @Test
    fun each_flash_setting_draws_its_own_mark() {
        assertEquals(
            listOf(FlashGlyph.Off, FlashGlyph.Auto, FlashGlyph.On),
            listOf(FlashMode.Off, FlashMode.Auto, FlashMode.On).map(::flashGlyph),
        )
    }

    /** A tap is handed to the camera as fractions of the viewfinder, across and then down. */
    @Test
    fun a_tap_becomes_fractions_of_the_viewfinder() {
        val size = IntSize(width = 400, height = 800)
        assertEquals(0.25f to 0.75f, focusFractions(Offset(100f, 600f), size))
        assertEquals(0f to 0f, focusFractions(Offset(0f, 0f), size))
        assertEquals(1f to 1f, focusFractions(Offset(400f, 800f), size))
    }

    /** Before the viewfinder has been measured there is nothing to divide by, and no focus point. */
    @Test
    fun an_unmeasured_viewfinder_takes_no_focus_point() {
        assertEquals(null, focusFractions(Offset(10f, 10f), IntSize.Zero))
    }

    /**
     * A screen wider than it is tall puts the controls beside the viewfinder rather than under it —
     * stacked under, they leave a landscape viewfinder a thin strip. A square screen keeps the
     * portrait layout, which is the one every other screen of the app uses.
     */
    @Test
    fun the_controls_go_beside_the_viewfinder_only_on_a_wide_screen() {
        assertEquals(CameraLayout.Beside, cameraLayout(IntSize(width = 3120, height = 1440)))
        assertEquals(CameraLayout.Below, cameraLayout(IntSize(width = 1440, height = 3120)))
        assertEquals(CameraLayout.Below, cameraLayout(IntSize(width = 1000, height = 1000)))
    }
}
