package dev.ehsanfahad.darkchamber.core.testing

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme

/**
 * Draws [content] the way the app does: inside the DarkChamber theme, so the Material components a screen
 * uses take the chamber's palette and the screen reads its roles as it would on a device.
 */
@ExperimentalTestApi
fun ComposeUiTest.setThemedContent(content: @Composable () -> Unit) {
    setContent { DarkChamberTheme(content = content) }
}

/** Matches a node announced as [role] — a button, a checkbox, a tab. */
fun hasRole(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

/**
 * The button labelled exactly [label] — never a title or a sentence that shares its words, and enabled or
 * not, so a test can say which. Find the label through the format function the screen calls, never retyped.
 */
fun SemanticsNodeInteractionsProvider.onButton(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and hasRole(Role.Button))

/**
 * Fails if any node shows [text], on its own or inside a longer sentence — for the lines a screen must not
 * say in a given state (a claim it cannot know, a message for another state).
 */
fun SemanticsNodeInteractionsProvider.assertNoText(text: String) {
    val showing = onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes()
    if (showing.isNotEmpty()) {
        throw AssertionError("Expected nothing to show \"$text\", but ${showing.size} node(s) do")
    }
}
