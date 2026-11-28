package dev.ehsanfahad.darkchamber.feature.vault.presentation

/** A text file's formatted view, beside its lines as stored. */
internal sealed interface FormattedText {
    /** A Markdown file, read into blocks to draw. */
    class Markdown(
        val document: MarkdownDocument,
    ) : FormattedText {
        override fun toString(): String = "Markdown($document)"
    }

    /** A JSON file re-indented — only whitespace changed — as lines, drawn as plain lines are. */
    class Json(
        val lines: DecodedText.Text,
    ) : FormattedText {
        override fun toString(): String = "Json($lines)"
    }
}
