package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.ExcludeFromPlatformStructures

/**
 * Jump to a page of the PDF shown: one number field, read by [PdfReading.parseJump]. A number the PDF
 * has goes there ([onGo], the page from 0); anything else is told why in the dialog, which stays — a
 * number out of range is never moved to the nearest page. The problem appears only on Go, and typing
 * clears it: the field does not argue with someone halfway through a number.
 */
@Composable
internal fun PdfJumpDialog(pageCount: Int, onGo: (Int) -> Unit, onCancel: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    var text by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }

    fun go() {
        when (val jump = PdfReading.parseJump(text, pageCount)) {
            is PageJump.Go -> onGo(jump.index)
            else -> problem = ViewerFormat.jumpProblem(jump, pageCount)
        }
    }

    Dialog(onDismissRequest = onCancel) {
        // Its own window, so its own exclusion from the platform's structures.
        ExcludeFromPlatformStructures()
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = colors.surface, shape = DarkChamberTheme.shapes.large)
                .padding(spacing.spaceLg),
        ) {
            Text(text = ViewerFormat.jumpTitle(), style = DarkChamberTheme.typography.title, color = colors.textPrimary)

            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    problem = null
                },
                label = { Text(ViewerFormat.jumpFieldLabel(pageCount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                isError = problem != null,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd).focusRequester(focus),
            )

            problem?.let { message ->
                Text(
                    text = message,
                    style = DarkChamberTheme.typography.caption,
                    color = colors.danger,
                    // Said as it appears: the field keeps the focus, so nothing else would announce it.
                    modifier = Modifier.padding(top = spacing.spaceXs).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd)) {
                TextButton(onClick = onCancel) {
                    Text(text = ViewerFormat.cancel(), style = DarkChamberTheme.typography.label, color = colors.textPrimary)
                }
                TextButton(onClick = { go() }) {
                    Text(text = ViewerFormat.jumpGo(), style = DarkChamberTheme.typography.label, color = colors.accent)
                }
            }
        }
    }
}

/** A jump asked for: a new object each time, so asking for the same page twice jumps twice. */
internal class PdfJumpRequest(val index: Int)
