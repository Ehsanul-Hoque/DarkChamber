package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** The theme's looks for the kinds of run a Markdown page draws apart from its words' own size. */
internal class MdRunStyles(
    val code: SpanStyle,
    val link: SpanStyle,
    val image: SpanStyle,
)

internal object MarkdownText {
    /**
     * [spans] as styled text: strong bold, emphasis italic, strike struck through, inline code and an
     * image's description in their own looks. A link that may do something — a web address, which goes
     * to the confirmation, or a `#section` in [sections] — is a clickable annotation that hands its
     * address to [onLink], and looks like a link; one that may not is shown as plain words, since
     * looking like a link would promise a tap that does nothing. Never `LinkAnnotation.Url`, which
     * opens an address by itself.
     */
    fun annotated(
        spans: List<MdSpan>,
        styles: MdRunStyles,
        sections: Map<String, Int>,
        onLink: (String) -> Unit,
    ): AnnotatedString =
        buildAnnotatedString {
            for (span in spans) {
                val text = if (span.image) ViewerFormat.imageText(span.text) else span.text
                val start = length
                append(text)
                val end = length
                if (span.strong) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                if (span.emphasis) addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                if (span.strike) addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                if (span.code) addStyle(styles.code, start, end)
                if (span.image) addStyle(styles.image, start, end)
                val address = span.link ?: continue
                if (LinkRules.target(address, sections) == LinkTarget.Inert) continue
                addLink(
                    LinkAnnotation.Clickable(tag = address, styles = TextLinkStyles(styles.link)) { onLink(address) },
                    start,
                    end,
                )
            }
        }
}
