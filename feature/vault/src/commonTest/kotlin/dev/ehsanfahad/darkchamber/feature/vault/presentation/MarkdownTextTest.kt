package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Markdown run's styles, and which links may be tapped. */
class MarkdownTextTest {
    private val styles = MdRunStyles(
        code = SpanStyle(color = Color.Green),
        link = SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline),
        image = SpanStyle(color = Color.Gray),
    )
    private val tapped = mutableListOf<String>()

    private fun text(vararg spans: MdSpan, sections: Map<String, Int> = emptyMap()) =
        MarkdownText.annotated(spans.toList(), styles, sections) { tapped += it }

    /** The merged style over [range] of [text]: every span style that covers all of it. */
    private fun AnnotatedString.styleOver(range: IntRange): List<SpanStyle> =
        spanStyles.filter { it.start <= range.first && it.end >= range.last + 1 }.map { it.item }

    private fun AnnotatedString.links() = getLinkAnnotations(0, length)

    @Test
    fun the_words_run_on_in_order() {
        assertEquals("plain bold end", text(MdSpan("plain "), MdSpan("bold", strong = true), MdSpan(" end")).text)
    }

    @Test
    fun each_emphasis_kind_has_its_own_style_only_where_it_is() {
        val t = text(MdSpan("a"), MdSpan("b", strong = true), MdSpan("c", emphasis = true), MdSpan("d", strike = true), MdSpan("e", code = true))
        assertEquals(emptyList(), t.styleOver(0..0))
        assertEquals(listOf(SpanStyle(fontWeight = FontWeight.Bold)), t.styleOver(1..1))
        assertEquals(listOf(SpanStyle(fontStyle = FontStyle.Italic)), t.styleOver(2..2))
        assertEquals(listOf(SpanStyle(textDecoration = TextDecoration.LineThrough)), t.styleOver(3..3))
        assertEquals(listOf(styles.code), t.styleOver(4..4))
    }

    @Test
    fun styles_combine_on_one_run() {
        val t = text(MdSpan("x", strong = true, emphasis = true, strike = true))
        val merged = t.styleOver(0..0).fold(SpanStyle()) { a, b -> a.merge(b) }
        assertEquals(FontWeight.Bold, merged.fontWeight)
        assertEquals(FontStyle.Italic, merged.fontStyle)
        assertEquals(TextDecoration.LineThrough, merged.textDecoration)
    }

    @Test
    fun an_image_shows_its_description_in_its_own_style() {
        val t = text(MdSpan("see "), MdSpan("a cat", image = true))
        assertEquals("see " + ViewerFormat.imageText("a cat"), t.text)
        assertEquals(listOf(styles.image), t.styleOver(4 until t.length))
    }

    @Test
    fun a_web_link_is_clickable_and_hands_over_its_address() {
        val t = text(MdSpan("go "), MdSpan("here", link = "https://example.com/x"))
        val link = t.links().single()
        assertEquals(3, link.start)
        assertEquals(7, link.end)
        val clickable = link.item as LinkAnnotation.Clickable
        assertEquals("https://example.com/x", clickable.tag)
        assertEquals(styles.link, clickable.styles?.style)
        clickable.linkInteractionListener!!.onClick(clickable)
        assertEquals(listOf("https://example.com/x"), tapped)
    }

    @Test
    fun a_section_link_to_a_heading_is_clickable_too() {
        val t = text(MdSpan("up", link = "#top"), sections = mapOf("top" to 0))
        assertEquals("#top", (t.links().single().item as LinkAnnotation.Clickable).tag)
    }

    /** Looking like a link would promise a tap that does nothing. */
    @Test
    fun an_inert_link_is_plain_words() {
        for (address in listOf("javascript:alert(1)", "mailto:a@example.com", "#nowhere", "relative/path")) {
            val t = text(MdSpan("x", link = address))
            assertTrue(t.links().isEmpty(), address)
            assertEquals(emptyList(), t.styleOver(0..0), address)
        }
    }

    @Test
    fun an_inert_link_keeps_its_emphasis() {
        val t = text(MdSpan("x", strong = true, link = "ftp://example.com"))
        assertEquals(listOf(SpanStyle(fontWeight = FontWeight.Bold)), t.styleOver(0..0))
    }

    /** One link split over runs of different styles is still each run's own tap, all to one address. */
    @Test
    fun a_link_over_several_runs_hands_over_the_same_address_from_each() {
        val t = text(MdSpan("a", link = "https://e.com"), MdSpan("b", strong = true, link = "https://e.com"))
        assertEquals(listOf("https://e.com", "https://e.com"), t.links().map { (it.item as LinkAnnotation.Clickable).tag })
    }

    @Test
    fun an_image_says_it_is_one_and_keeps_its_place_without_a_description() {
        assertEquals("[Image: a cat]", ViewerFormat.imageText("a cat"))
        assertEquals("[Image]", ViewerFormat.imageText(""))
        assertEquals("[Image]", ViewerFormat.imageText("  "))
    }
}
