package dev.ehsanfahad.darkchamber.feature.vault.api

import dev.ehsanfahad.darkchamber.feature.vault.presentation.DecodedText
import dev.ehsanfahad.darkchamber.feature.vault.presentation.FormattedText

/**
 * A text file as the viewer shows it: [plain], its lines as stored, and [formatted] — Markdown drawn or
 * JSON re-indented — where it has one. Vault content, so held apart from screen state (see
 * [VaultViewerComponent.texts]); its contents are for this feature's own screen only.
 */
class TextDocument internal constructor(
    internal val plain: DecodedText.Text,
    internal val formatted: FormattedText?,
) {
    /** Nothing of the text: it is vault content. */
    override fun toString(): String = "TextDocument(${plain.lineCount} lines, formatted=${formatted != null})"
}
