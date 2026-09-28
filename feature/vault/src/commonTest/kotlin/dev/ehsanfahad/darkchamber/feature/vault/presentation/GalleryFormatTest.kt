package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun a_finished_batch_counts_honestly_and_reads_as_english() {
        assertEquals("1 file added to the vault.", GalleryFormat.importedCount(1))
        assertEquals("48 files added to the vault.", GalleryFormat.importedCount(48))
        // Not "0 files added": a batch where everything failed should not read like a success with
        // an awkward number in it.
        assertEquals("Nothing was added.", GalleryFormat.importedCount(0))
    }

    @Test
    fun unreadable_files_are_counted_without_claiming_why() {
        assertEquals("1 file couldn't be read.", GalleryFormat.failedCount(1))
        assertEquals("2 files couldn't be read.", GalleryFormat.failedCount(2))
    }

    @Test
    fun the_delete_offer_says_how_many_originals_it_means() {
        assertEquals("Delete original", GalleryFormat.deleteOriginals(1))
        assertEquals("Delete 12 originals", GalleryFormat.deleteOriginals(12))
    }

    @Test
    fun a_partial_deletion_is_reported_as_a_partial_deletion() {
        assertEquals("3 originals deleted.", GalleryFormat.deletedOriginals(deleted = 3, requested = 3))
        assertEquals("1 original deleted.", GalleryFormat.deletedOriginals(deleted = 1, requested = 1))
        // The honest shape of a provider that took some and refused the rest. Rounding this up to
        // "3 deleted" would tell someone a file is gone while it is still sitting there.
        assertEquals("2 of 3 originals deleted.", GalleryFormat.deletedOriginals(deleted = 2, requested = 3))
        assertEquals("No originals were deleted.", GalleryFormat.deletedOriginals(deleted = 0, requested = 3))
    }

    /**
     * The disclosure's copy, checked against what `docs/THREAT_MODEL.md` actually permits.
     *
     * DESIGN §11 is explicit for the shredder — "reduces the chance of recovery", never "permanently
     * erases" — and deletion here is weaker still: the app asks the system, and the system decides.
     * Wear-levelling can leave copies of the data in flash the filesystem never addresses again.
     * This test is the guard on the wording, because nothing else would notice it drifting.
     */
    @Test
    fun the_disclosure_claims_only_what_we_can_stand_behind() {
        val copy = GalleryFormat.deleteOriginalsDisclosure(3).lowercase()

        assertTrue("reduce" in copy, "it must say the claim it can make, got: $copy")
        assertTrue("wear" in copy || "flash" in copy, "the limitation has to be stated, got: $copy")
        for (overclaim in listOf("permanent", "erase", "unrecoverable", "securely", "forever", "wipe")) {
            assertFalse(overclaim in copy, "the copy promises more than the threat model allows: $overclaim")
        }
    }

    @Test
    fun the_disclosure_says_the_vault_keeps_its_own_copy() {
        // Because the question a user actually has at that dialog is "am I about to lose this?"
        val copy = GalleryFormat.deleteOriginalsDisclosure(1).lowercase()
        assertTrue("vault" in copy && "encrypted" in copy, "got: $copy")
    }
}
