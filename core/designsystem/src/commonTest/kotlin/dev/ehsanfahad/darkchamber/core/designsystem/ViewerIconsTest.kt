package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The viewer's marks carry their supplied drawings unchanged.
 *
 * **Exact counts, taken from the exports themselves** (counted in the supplied files, not in this
 * module's copy), as for the camera's marks: a threshold would only hide a pruned command.
 */
class ViewerIconsTest {

    private class Expected(val vector: ImageVector, val commands: Int, val subpaths: Int)

    private val marks = mapOf(
        "arrow_back_ios" to Expected(backVector, commands = 8, subpaths = 1),
        "info" to Expected(detailsVector, commands = 46, subpaths = 5),
        "delete_forever" to Expected(deleteVector, commands = 42, subpaths = 4),
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
}
