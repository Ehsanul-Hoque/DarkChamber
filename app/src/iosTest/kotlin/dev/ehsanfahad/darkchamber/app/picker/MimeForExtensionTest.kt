package dev.ehsanfahad.darkchamber.app.picker

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The type an iOS import records from a file's extension. The picker gives files, not types, so this is
 * what the vault stores; the viewer reads a text file by its extension as well, so a type here matters
 * for what is recorded and shown in Details, and for a viewer that goes by type alone.
 */
class MimeForExtensionTest {

    @Test
    fun text_files_record_their_type() {
        val expected = mapOf(
            "txt" to "text/plain",
            "text" to "text/plain",
            "md" to "text/markdown",
            "markdown" to "text/markdown",
            "mdown" to "text/markdown",
            "mkd" to "text/markdown",
            "json" to "application/json",
            "html" to "text/html",
            "htm" to "text/html",
            "xhtml" to "application/xhtml+xml",
            "xml" to "application/xml",
            "csv" to "text/csv",
            "tsv" to "text/tab-separated-values",
            "yaml" to "application/yaml",
            "yml" to "application/yaml",
            "toml" to "application/toml",
            "vtt" to "text/vtt",
            "css" to "text/css",
            "js" to "text/javascript",
            "sh" to "application/x-sh",
            "sql" to "application/sql",
        )
        expected.forEach { (extension, type) -> assertEquals(type, mimeForExtension(extension), extension) }
    }

    /** Config, logs, subtitles and source with no registered type of their own: plain text, which they are. */
    @Test
    fun text_with_no_registered_type_records_plain_text() {
        listOf(
            "ini", "cfg", "conf", "properties", "log", "srt",
            "kt", "kts", "java", "swift", "py", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "gradle",
        ).forEach { assertEquals("text/plain", mimeForExtension(it), it) }
    }

    /** `.ts` is also an MPEG transport stream — a video — so it is not recorded as text. */
    @Test
    fun an_extension_shared_with_video_is_not_recorded_as_text() {
        assertEquals("application/octet-stream", mimeForExtension("ts"))
    }

    @Test
    fun the_extension_is_read_in_any_case() {
        assertEquals("application/json", mimeForExtension("JSON"))
        assertEquals("text/markdown", mimeForExtension("Md"))
        assertEquals("text/html", mimeForExtension("HTM"))
    }

    @Test
    fun anything_else_is_honestly_unknown() {
        for (extension in listOf("bin", "zip", "", "docx", "jsonx")) {
            assertEquals("application/octet-stream", mimeForExtension(extension), extension)
        }
        assertEquals("application/octet-stream", mimeForExtension(null))
    }

    /** What was mapped before is unchanged. */
    @Test
    fun the_media_types_are_unchanged() {
        assertEquals("image/jpeg", mimeForExtension("jpg"))
        assertEquals("video/mp4", mimeForExtension("m4v"))
        assertEquals("audio/mp4", mimeForExtension("m4a"))
        assertEquals("application/pdf", mimeForExtension("pdf"))
    }
}
