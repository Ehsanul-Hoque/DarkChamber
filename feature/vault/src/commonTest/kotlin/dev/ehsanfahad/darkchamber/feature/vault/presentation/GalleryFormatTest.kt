package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.ImportStage
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

    /**
     * Only the stages that cannot be measured are named. The encrypt is the ordinary case and the
     * card's heading already says which file of how many, so labelling it would add a line to every
     * import that tells the reader nothing.
     */
    @Test
    fun only_the_stages_with_nothing_to_measure_are_named() {
        assertEquals(null, GalleryFormat.stageLabel(ImportStage.ENCRYPTING), "the ordinary case says nothing")
        assertEquals(null, GalleryFormat.stageLabel(null), "and neither does a stage not worth naming yet")

        for (stage in listOf(ImportStage.FETCHING, ImportStage.COPYING, ImportStage.FINISHING)) {
            assertTrue(
                GalleryFormat.stageLabel(stage)?.isNotBlank() == true,
                "a stage with no bar to draw has to explain itself, got none for $stage",
            )
        }
    }

    /**
     * The wait must not be called a download. We cannot tell a provider downloading a file from one
     * that is merely slow — no Android API says — and naming the wrong one is exactly the kind of
     * small lie hard rule 6 exists to prevent. This test is the guard on that wording, because
     * nothing else would notice it drifting.
     */
    @Test
    fun the_wait_does_not_claim_to_know_what_the_platform_is_doing() {
        val copy = GalleryFormat.stageLabel(ImportStage.FETCHING)!!.lowercase()

        assertTrue("waiting" in copy, "it says what we know — that we are waiting — got: $copy")
        for (guess in listOf("download", "fetch", "cloud", "network", "server")) {
            assertFalse(guess in copy, "the label claims something we cannot know: $guess")
        }
    }

    /** Each named stage says something different, or the label is decoration rather than information. */
    @Test
    fun the_named_stages_are_told_apart() {
        val labels = listOf(ImportStage.FETCHING, ImportStage.COPYING, ImportStage.FINISHING)
            .map { GalleryFormat.stageLabel(it) }

        assertEquals(labels.size, labels.distinct().size, "two stages reading alike explain nothing: $labels")
    }
}
