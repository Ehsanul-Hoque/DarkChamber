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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.ExcludeFromPlatformStructures
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraft

/**
 * Name an album: one dialog for creating and for renaming.
 *
 * Shared because the two are the same interaction — one field, the same name rule, the same four
 * states (typing, refused, writing, failed) — differing only in what they are called and what their
 * failure means. Two copies would be two places for the keyboard action, the disabled-while-writing
 * rule and the problem line to drift apart.
 *
 * Dismissable by back and by tapping outside, unlike the import disclosure: nothing here has to be
 * read before acting, and the worst a stray dismissal costs is a name worth retyping. **Except while
 * the write runs** — then neither gesture is honoured, because the vault is already writing and a
 * dialog that vanished would be claiming otherwise.
 */
@Composable
internal fun AlbumNameDialog(
    title: String,
    draft: AlbumDraft,
    // What the confirm button says, and what it says instead once it has been pressed — the only thing
    // on screen explaining why the dialog has stopped responding.
    confirmLabel: String,
    savingLabel: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = !draft.saving,
            dismissOnClickOutside = !draft.saving,
        ),
    ) {
        // A dialog is its own window, and the host's exclusion does not reach it — measured: the
        // field below still turned up in a fill request until this was here. The album's name is
        // vault content, so this is the dialog that most needs it.
        ExcludeFromPlatformStructures()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = colors.surface, shape = DarkChamberTheme.shapes.large)
                .padding(spacing.spaceLg),
        ) {
            Text(text = title, style = DarkChamberTheme.typography.title, color = colors.textPrimary)

            OutlinedTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = { Text(GalleryFormat.albumNameLabel()) },
                singleLine = true,
                // The field is the whole dialog, so the keyboard's action is the dialog's: someone who
                // typed a name and pressed Done meant to confirm it.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                isError = draft.problem != null,
                enabled = !draft.saving,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd),
            )

            draft.problem?.let { problem ->
                Text(
                    text = GalleryFormat.albumDraftProblem(problem),
                    style = DarkChamberTheme.typography.caption,
                    color = colors.danger,
                    modifier = Modifier.padding(top = spacing.spaceXs),
                )
            }

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd),
            ) {
                TextButton(onClick = onCancel, enabled = !draft.saving) {
                    Text(
                        text = "Cancel",
                        style = DarkChamberTheme.typography.label,
                        color = if (draft.saving) colors.textSecondary else colors.textPrimary,
                    )
                }
                TextButton(onClick = onConfirm, enabled = !draft.saving) {
                    // Nothing destructive happens here, so neither button takes the danger role.
                    Text(
                        text = if (draft.saving) savingLabel else confirmLabel,
                        style = DarkChamberTheme.typography.label,
                        color = if (draft.saving) colors.textSecondary else colors.accent,
                    )
                }
            }
        }
    }
}
