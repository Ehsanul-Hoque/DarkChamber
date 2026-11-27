package dev.ehsanfahad.darkchamber.core.testing

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The helpers every UI test shares, each held to what its name promises. */
class UiFindersUiTest : UiTest() {
    @Test
    fun themed_content_hands_material_components_the_chamber_palette() =
        runComposeUiTest {
            var primary: Color? = null
            var accent: Color? = null
            setThemedContent {
                primary = MaterialTheme.colorScheme.primary
                accent = DarkChamberTheme.colors.accent
            }
            waitForIdle()

            assertNotNull(accent)
            assertEquals(accent, primary, "a Material button under test must be drawn in the chamber's accent")
        }

    @Test
    fun has_role_matches_only_nodes_announced_as_that_role() =
        runComposeUiTest {
            setContent {
                Column {
                    Text("Plain")
                    Button(onClick = {}) { Text("Go") }
                    Checkbox(checked = false, onCheckedChange = {})
                }
            }

            onAllNodes(hasRole(Role.Button)).assertCountEquals(1)
            onAllNodes(hasRole(Role.Button) and hasText("Go")).assertCountEquals(1)
            onAllNodes(hasRole(Role.Checkbox)).assertCountEquals(1)
        }

    @Test
    fun on_button_finds_the_button_and_not_a_title_with_the_same_word() =
        runComposeUiTest {
            var pressed = 0
            setContent {
                Column {
                    Text("Delete")
                    Button(onClick = { pressed++ }) { Text("Delete") }
                }
            }

            onButton("Delete").performClick()

            assertEquals(1, pressed)
        }

    @Test
    fun on_button_matches_the_whole_label_not_a_longer_one_starting_with_it() =
        runComposeUiTest {
            var pressed = ""
            setContent {
                Column {
                    Button(onClick = { pressed = "all" }) { Text("Delete all") }
                    Button(onClick = { pressed = "one" }) { Text("Delete") }
                }
            }

            onButton("Delete").performClick()

            assertEquals("one", pressed)
        }

    @Test
    fun on_button_finds_a_disabled_button_so_a_test_can_say_it_is_disabled() =
        runComposeUiTest {
            setContent { Button(onClick = {}, enabled = false) { Text("Delete") } }

            onButton("Delete").assertIsNotEnabled()
        }

    @Test
    fun assert_no_text_passes_when_nothing_shows_the_text() =
        runComposeUiTest {
            setContent { Text("Nothing in the vault yet") }

            assertNoText("couldn't be read")
        }

    @Test
    fun assert_no_text_fails_on_the_text_inside_a_longer_sentence_and_names_it() =
        runComposeUiTest {
            setContent { Text("The vault's listing couldn't be read.") }

            val failure = assertFailsWith<AssertionError> { assertNoText("couldn't be read") }

            assertTrue(
                "couldn't be read" in failure.message.orEmpty(),
                "the failure names the text: ${failure.message}",
            )
        }
}
