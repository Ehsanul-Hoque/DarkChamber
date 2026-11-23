package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which items the viewer reads as text, decided from the stored details before anything is read.
 * The stored type depends on where a file came from — one provider records a Markdown file as
 * `text/markdown`, another as `application/octet-stream` — so the name's extension counts as well.
 */
class TextKindTest {

    private fun item(name: String, mime: String) = GalleryItemMeta.Readable(name = name, mime = mime)

    @Test
    fun a_stored_text_type_is_text_whatever_the_name() {
        assertEquals(TextKind.Plain, textKind(item("notes", "text/plain")))
        assertEquals(TextKind.Markdown, textKind(item("README", "text/markdown")))
        assertEquals(TextKind.Markdown, textKind(item("README", "text/x-markdown")))
        assertEquals(TextKind.Json, textKind(item("data", "application/json")))
        assertEquals(TextKind.Json, textKind(item("data", "application/ld+json")))
        assertEquals(TextKind.Code, textKind(item("page", "text/html")))
        assertEquals(TextKind.Code, textKind(item("table", "text/csv")))
        assertEquals(TextKind.Code, textKind(item("feed", "application/xml")))
        assertEquals(TextKind.Code, textKind(item("feed", "application/atom+xml")))
        assertEquals(TextKind.Code, textKind(item("config", "application/yaml")))
    }

    /** The whole list, pinned: an entry dropped or moved to another kind is a file shown differently. */
    @Test
    fun every_listed_type_and_extension_has_its_kind() {
        val types = mapOf(
            "text/plain" to TextKind.Plain,
            "text/markdown" to TextKind.Markdown,
            "text/x-markdown" to TextKind.Markdown,
            "application/json" to TextKind.Json,
            "application/geo+json" to TextKind.Json,
            "text/x-anything" to TextKind.Code,
            "application/xml" to TextKind.Code,
            "application/yaml" to TextKind.Code,
            "application/x-yaml" to TextKind.Code,
            "application/toml" to TextKind.Code,
            "application/javascript" to TextKind.Code,
            "application/x-sh" to TextKind.Code,
            "application/sql" to TextKind.Code,
            "application/rss+xml" to TextKind.Code,
        )
        types.forEach { (type, kind) -> assertEquals(kind, textKind(item("noext", type)), type) }

        val plain = listOf("txt", "text")
        val markdown = listOf("md", "markdown", "mdown", "mkd")
        val code = listOf(
            "html", "htm", "xhtml", "xml", "csv", "tsv", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "properties", "log", "srt", "vtt",
            "kt", "kts", "java", "swift", "py", "js", "ts", "c", "h", "cpp", "hpp", "cs", "go", "rs",
            "rb", "php", "sh", "sql", "css", "gradle",
        )
        val unknown = "application/octet-stream"
        plain.forEach { assertEquals(TextKind.Plain, textKind(item("f.$it", unknown)), it) }
        markdown.forEach { assertEquals(TextKind.Markdown, textKind(item("f.$it", unknown)), it) }
        assertEquals(TextKind.Json, textKind(item("f.json", unknown)))
        code.forEach { assertEquals(TextKind.Code, textKind(item("f.$it", unknown)), it) }
    }

    /** A provider may add parameters or capitals; the type is the part before them, in any case. */
    @Test
    fun a_stored_type_is_read_without_its_parameters_or_case() {
        assertEquals(TextKind.Plain, textKind(item("notes", "text/plain; charset=utf-8")))
        assertEquals(TextKind.Json, textKind(item("data", "Application/JSON")))
        assertEquals(TextKind.Markdown, textKind(item("README", " text/markdown ;charset=UTF-8")))
    }

    /** What iOS records for anything but txt and md, and what some Android providers record for all. */
    @Test
    fun a_known_extension_makes_an_unknown_type_text() {
        val unknown = "application/octet-stream"
        assertEquals(TextKind.Markdown, textKind(item("notes.md", unknown)))
        assertEquals(TextKind.Markdown, textKind(item("notes.markdown", unknown)))
        assertEquals(TextKind.Plain, textKind(item("notes.txt", unknown)))
        assertEquals(TextKind.Json, textKind(item("data.json", unknown)))
        assertEquals(TextKind.Code, textKind(item("page.html", unknown)))
        assertEquals(TextKind.Code, textKind(item("page.htm", unknown)))
        assertEquals(TextKind.Code, textKind(item("feed.xml", unknown)))
        assertEquals(TextKind.Code, textKind(item("table.csv", unknown)))
        assertEquals(TextKind.Code, textKind(item("run.log", unknown)))
        assertEquals(TextKind.Code, textKind(item("config.yaml", unknown)))
        assertEquals(TextKind.Code, textKind(item("config.yml", unknown)))
        assertEquals(TextKind.Code, textKind(item("Main.kt", unknown)))
        assertEquals(TextKind.Code, textKind(item("tool.py", "")), "no stored type at all")
    }

    /** The extension says more than a generic text type: a `.json` saved as plain text is still JSON. */
    @Test
    fun a_known_extension_decides_the_kind_of_a_text_type() {
        assertEquals(TextKind.Json, textKind(item("data.json", "text/plain")))
        assertEquals(TextKind.Markdown, textKind(item("notes.md", "text/plain")))
        assertEquals(TextKind.Code, textKind(item("run.log", "text/plain")))
        assertEquals(TextKind.Plain, textKind(item("notes.txt", "text/markdown")))
    }

    @Test
    fun an_extension_is_matched_in_any_case_and_only_after_the_last_dot() {
        assertEquals(TextKind.Markdown, textKind(item("NOTES.MD", "application/octet-stream")))
        assertEquals(TextKind.Json, textKind(item("Data.Json", "application/octet-stream")))
        assertEquals(TextKind.Code, textKind(item("backup.md.log", "application/octet-stream")))
        assertNull(textKind(item("notes.md.zip", "application/octet-stream")))
    }

    @Test
    fun a_name_without_a_known_extension_leaves_it_to_the_type() {
        assertNull(textKind(item("README", "application/octet-stream")))
        assertNull(textKind(item("md", "application/octet-stream")), "a name that is only the extension's letters")
        assertNull(textKind(item("notes.", "application/octet-stream")))
        assertNull(textKind(item("archive.zip", "application/zip")))
        assertNull(textKind(item("db.sqlite", "application/x-sqlite3")))
    }

    /** A kind with its own viewer keeps it: a picture named `.txt` is still a picture. */
    @Test
    fun a_stored_media_or_pdf_type_is_never_text_whatever_the_name() {
        assertNull(textKind(item("photo.txt", "image/jpeg")))
        assertNull(textKind(item("drawing.xml", "image/svg+xml")))
        assertNull(textKind(item("clip.md", "video/mp4")))
        assertNull(textKind(item("song.log", "audio/mpeg")))
        assertNull(textKind(item("paper.txt", "application/pdf")))
    }

    /** Its type is unknown and the name is sealed with it, so nothing says it is text. */
    @Test
    fun an_item_whose_details_will_not_open_is_not_text() {
        assertNull(textKind(GalleryItemMeta.Unreadable))
    }

    @Test
    fun text_is_read_up_to_eight_mebibytes_and_not_a_byte_more() {
        assertEquals(8L * 1024 * 1024, TEXT_CAP)
        assertEquals(OpenPlan.Decode, textPlan(TEXT_CAP), "at the cap is within it")
        assertEquals(OpenPlan.Show(ViewerPage.TooLarge), textPlan(TEXT_CAP + 1))
        assertEquals(OpenPlan.Decode, textPlan(1))
    }

    /** An empty file is text with nothing in it, not a failure. */
    @Test
    fun an_empty_file_is_read() {
        assertEquals(OpenPlan.Decode, textPlan(0))
    }

    /** A row nothing here wrote: nothing is read, and the page names no cause. */
    @Test
    fun a_negative_size_reads_nothing() {
        assertEquals(OpenPlan.Show(ViewerPage.CouldntOpen), textPlan(-1))
    }
}
