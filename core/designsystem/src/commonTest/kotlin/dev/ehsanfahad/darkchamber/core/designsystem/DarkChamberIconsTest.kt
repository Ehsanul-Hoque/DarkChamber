package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The album mark, asserted as a value rather than as a drawing.
 *
 * There is no screenshot test in this project, so a vector that builds to nothing renders as nothing
 * and the suite says the icon is fine. These checks are cheap and catch the two ways that happens:
 * path data lost in a move like this one, and a viewport that does not match the drawing's
 * coordinates — which scales the paths out of the box they are drawn in.
 */
class DarkChamberIconsTest {

    @Test
    fun the_album_mark_carries_its_drawing() {
        val paths = albumMarkVector.root.filterIsInstance<VectorPath>()

        assertEquals(1, paths.size, "one path, as supplied")
        // **An exact count, deliberately.** A "more than n" threshold passed with five commands
        // deleted, which is the whole failure this test exists for — the drawing is fixed data
        // carried in, not something expected to drift, so redrawing it means updating this number on
        // purpose rather than losing part of it quietly.
        assertEquals(ALBUM_MARK_COMMANDS, paths.single().pathData.size)
        assertTrue(
            paths.single().pathData.count { it is PathNode.Close } == ALBUM_MARK_SUBPATHS,
            "five closed subpaths: the two frames, the picture inside, the back frame and one " +
                "degenerate line that came with it — ${paths.single().pathData}",
        )
    }

    @Test
    fun the_album_mark_is_drawn_in_the_box_it_is_sized_for() {
        assertEquals(24.dp, albumMarkVector.defaultWidth)
        assertEquals(24.dp, albumMarkVector.defaultHeight)
        // A viewport that disagrees with the path coordinates is the other way this renders wrong
        // while still "working" — the drawing is in a 24-unit square.
        assertEquals(24f, albumMarkVector.viewportWidth)
        assertEquals(24f, albumMarkVector.viewportHeight)
    }

    @Test
    fun the_disclosure_caret_carries_its_drawing() {
        val paths = disclosureCaretVector.root.filterIsInstance<VectorPath>()

        assertEquals(1, paths.size, "one path, as supplied")
        // Exact, for the same reason as the album mark above: the drawing is fixed data carried in,
        // so losing part of it must fail rather than render a shorter chevron nobody notices.
        assertEquals(CARET_COMMANDS, paths.single().pathData.size)
        assertEquals(
            CARET_SUBPATHS,
            paths.single().pathData.count { it is PathNode.Close },
            "one closed subpath — the chevron is a single outline: ${paths.single().pathData}",
        )
    }

    @Test
    fun the_disclosure_caret_is_drawn_in_the_box_it_is_sized_for() {
        assertEquals(24.dp, disclosureCaretVector.defaultWidth)
        assertEquals(24.dp, disclosureCaretVector.defaultHeight)
        assertEquals(24f, disclosureCaretVector.viewportWidth)
        assertEquals(24f, disclosureCaretVector.viewportHeight)
    }

    private companion object {
        /** Every command in the supplied drawing. See the exact-count note above. */
        const val ALBUM_MARK_COMMANDS = 41

        /** How many of those commands close a subpath. */
        const val ALBUM_MARK_SUBPATHS = 5

        /** The chevron: a move, six lines and a close. */
        const val CARET_COMMANDS = 8

        const val CARET_SUBPATHS = 1
    }
}
