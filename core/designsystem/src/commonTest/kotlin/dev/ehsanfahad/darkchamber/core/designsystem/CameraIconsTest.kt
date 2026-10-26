package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The camera's marks carry their supplied drawings unchanged.
 *
 * **Exact counts, taken from the exports themselves** (counted in the supplied files, not in this
 * module's copy): committed path data does not drift, so a threshold would only hide a pruned
 * command. Each is one path, as supplied, in a 24-unit box.
 */
class CameraIconsTest {

    private class Expected(val vector: ImageVector, val commands: Int, val subpaths: Int)

    private val marks = mapOf(
        "camera" to Expected(cameraVector, commands = 64, subpaths = 8),
        "close" to Expected(closeVector, commands = 14, subpaths = 1),
        "flash_off" to Expected(flashOffVector, commands = 28, subpaths = 3),
        "flash_auto" to Expected(flashAutoVector, commands = 38, subpaths = 5),
        "flash_on" to Expected(flashOnVector, commands = 22, subpaths = 3),
        "flip_camera_android" to Expected(switchCameraVector, commands = 44, subpaths = 3),
        "flashlight_on" to Expected(torchOnVector, commands = 38, subpaths = 5),
        "flashlight_off" to Expected(torchOffVector, commands = 37, subpaths = 5),
        "mic_on" to Expected(microphoneOnVector, commands = 41, subpaths = 4),
        "mic_off" to Expected(microphoneOffVector, commands = 49, subpaths = 5),
    )

    @Test
    fun each_mark_carries_its_supplied_drawing_exactly() {
        for ((name, expected) in marks) {
            val paths = expected.vector.root.filterIsInstance<VectorPath>()
            assertEquals(1, paths.size, "$name: one path, as supplied")
            val data = paths.single().pathData
            assertEquals(expected.commands, data.size, "$name: every command, none pruned")
            assertEquals(expected.subpaths, data.count { it is PathNode.Close }, "$name: every subpath closed")
        }
    }

    @Test
    fun each_mark_is_drawn_in_the_box_it_is_sized_for() {
        for ((name, expected) in marks) {
            val v = expected.vector
            assertEquals(24.dp, v.defaultWidth, name)
            assertEquals(24.dp, v.defaultHeight, name)
            assertEquals(24f, v.viewportWidth, name)
            assertEquals(24f, v.viewportHeight, name)
        }
    }

    /**
     * Each flash state draws its own mark. A mix-up here would show the flash as off while it fires,
     * which no render of a single icon would reveal.
     */
    @Test
    fun each_flash_state_draws_its_own_mark() {
        assertSame(flashOffVector, flashVector(FlashGlyph.Off))
        assertSame(flashAutoVector, flashVector(FlashGlyph.Auto))
        assertSame(flashOnVector, flashVector(FlashGlyph.On))
    }

    /** As the flash: a torch drawn as off while it is lit is a mark that says the opposite of the truth. */
    @Test
    fun each_torch_state_draws_its_own_mark() {
        assertSame(torchOnVector, torchVector(on = true))
        assertSame(torchOffVector, torchVector(on = false))
    }

    /** The no-sound mark is the crossed-out microphone; sound on is the plain one. */
    @Test
    fun each_microphone_state_draws_its_own_mark() {
        assertSame(microphoneOnVector, microphoneVector(on = true))
        assertSame(microphoneOffVector, microphoneVector(on = false))
    }
}
