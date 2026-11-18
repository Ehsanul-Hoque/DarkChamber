package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The player's marks carry their supplied drawings unchanged.
 *
 * **Exact counts, taken from the exports themselves** (counted in the supplied files, not in this
 * module's copy), as for the viewer's and the camera's marks: a threshold would only hide a pruned
 * command.
 */
class PlayerIconsTest {

    private class Expected(val vector: ImageVector, val commands: Int, val subpaths: Int)

    private val marks = mapOf(
        "play_arrow" to Expected(playVector, commands = 12, subpaths = 3),
        "pause" to Expected(pauseVector, commands = 32, subpaths = 6),
        "replay" to Expected(replayVector, commands = 29, subpaths = 1),
        "screen_rotation_alt" to Expected(rotationVector, commands = 30, subpaths = 2),
        "brightness_medium" to Expected(brightnessVector, commands = 45, subpaths = 4),
        "volume_up" to Expected(volumeVector, commands = 37, subpaths = 5),
        "volume_off" to Expected(mutedVector, commands = 53, subpaths = 6),
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

    @Test
    fun the_volume_mark_crosses_out_the_speaker_only_when_muted() {
        assertSame(mutedVector, volumeMarkVector(muted = true))
        assertSame(volumeVector, volumeMarkVector(muted = false))
    }
}
