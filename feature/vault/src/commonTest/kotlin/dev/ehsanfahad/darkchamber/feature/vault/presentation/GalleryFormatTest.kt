package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class GalleryFormatTest {

    @Test
    fun type_badges_follow_the_mime_family() {
        assertEquals("IMG", GalleryFormat.typeBadge("image/heic"))
        assertEquals("VID", GalleryFormat.typeBadge("video/mp4"))
        assertEquals("AUD", GalleryFormat.typeBadge("audio/mpeg"))
        assertEquals("PDF", GalleryFormat.typeBadge("application/pdf"))
        assertEquals("TXT", GalleryFormat.typeBadge("text/markdown"))
    }

    /** An unknown type gets an honest label, not a guess at what the file might be. */
    @Test
    fun an_unknown_mime_falls_back_to_a_neutral_badge() {
        assertEquals("FILE", GalleryFormat.typeBadge("application/x-sqlite3"))
        assertEquals("FILE", GalleryFormat.typeBadge(""))
    }

    @Test
    fun sizes_read_in_binary_units() {
        assertEquals("0 B", GalleryFormat.size(0))
        assertEquals("512 B", GalleryFormat.size(512))
        assertEquals("1.0 KiB", GalleryFormat.size(1024))
        assertEquals("1.5 KiB", GalleryFormat.size(1536))
        assertEquals("1.0 MiB", GalleryFormat.size(1024L * 1024))
        assertEquals("2.5 GiB", GalleryFormat.size((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun ages_are_coarse() {
        val now = 1_000_000_000L
        assertEquals("just now", GalleryFormat.age(now - 30_000, now))
        assertEquals("5m ago", GalleryFormat.age(now - 5 * 60_000, now))
        assertEquals("3h ago", GalleryFormat.age(now - 3 * 3_600_000, now))
        assertEquals("2d ago", GalleryFormat.age(now - 2 * 86_400_000L, now))
        assertEquals("3w ago", GalleryFormat.age(now - 21 * 86_400_000L, now))
    }

    /** A clock that moved backwards must not render a negative age. */
    @Test
    fun a_future_timestamp_reads_as_just_now() {
        assertEquals("just now", GalleryFormat.age(createdAt = 2_000, now = 1_000))
    }
}
