package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraftProblem
import dev.ehsanfahad.darkchamber.feature.vault.api.FilingDestination
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /**
     * Failed files are counted **without naming a cause**.
     *
     * This line used to say "couldn't be read", which was true while an unreadable source was the only
     * way an import could fail. An import can now be filed into an album as it is created, so a
     * destination that disappeared mid-batch fails the file as well — and blaming the file for that
     * would send someone hunting for corruption that is not there.
     */
    @Test
    fun failed_files_are_counted_without_claiming_why() {
        assertEquals("1 file wasn't added.", GalleryFormat.failedCount(1))
        assertEquals("2 files weren't added.", GalleryFormat.failedCount(2))

        for (count in listOf(1, 3)) {
            val copy = GalleryFormat.failedCount(count).lowercase()
            for (cause in listOf("read", "corrupt", "album", "too big")) {
                assertFalse(cause in copy, "it does not name a cause it cannot know: $copy")
            }
        }
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

    /**
     * An empty grid is three situations, and the copy is the only thing that tells them apart. The
     * failure this guards is specific: saying "nothing in the vault yet" inside an empty album tells
     * someone with five hundred encrypted files that their vault is empty.
     */
    @Test
    fun each_empty_listing_says_something_different() {
        val vault = GalleryFormat.emptyListing(GalleryScope.WholeVault, unreadable = false)
        val album = GalleryFormat.emptyListing(GalleryScope.Album, unreadable = false)
        val unfiled = GalleryFormat.emptyListing(GalleryScope.Unfiled, unreadable = false)

        assertEquals(3, setOf(vault, album, unfiled).size, "three situations, three answers")
        assertTrue(
            "nothing in the vault" in vault.lowercase(),
            "the empty vault is the only one that may say the vault is empty: $vault",
        )
        // Mentioning the vault is fine and useful — the album copy says where to select items from.
        // What must not happen is either of these telling a user with a full vault that it is empty.
        assertFalse("nothing in the vault" in album.lowercase(), "an empty album is not an empty vault: $album")
        assertFalse("nothing in the vault" in unfiled.lowercase(), "nor is a fully filed one: $unfiled")
        assertTrue("album" in album.lowercase(), "and it names what is actually empty: $album")
    }

    /**
     * Nothing unfiled means everything **has** been filed. That is a success, so it must not read as
     * a lack — "no items" there would make a tidy vault sound broken.
     */
    @Test
    fun nothing_unfiled_reads_as_an_achievement_rather_than_an_absence() {
        val copy = GalleryFormat.emptyListing(GalleryScope.Unfiled, unreadable = false).lowercase()

        assertTrue("filed" in copy, "it says what is true — everything is filed: $copy")
        for (lack in listOf("no items", "nothing in", "empty")) {
            assertFalse(lack in copy, "a fully filed vault is not a shortage: $copy")
        }
    }

    /**
     * **A listing that could not be read is not a listing with nothing in it**, and the grid is empty
     * in both cases. Every one of the three empty-listing answers asserts something about the vault's
     * contents, which is exactly what a failed read cannot know: "nothing in the vault yet" after the
     * index failed to open tells someone their files are gone.
     *
     * So the unreadable answer is the same whatever the scope — it describes the read, not the vault —
     * and claims nothing about what is or is not in there.
     */
    @Test
    fun an_unreadable_listing_claims_nothing_about_what_is_in_the_vault() {
        val scopes = listOf(GalleryScope.WholeVault, GalleryScope.Album, GalleryScope.Unfiled)

        val answers = scopes.map { GalleryFormat.emptyListing(it, unreadable = true) }
        assertEquals(1, answers.toSet().size, "the read failed; the scope tells us nothing more")

        val copy = answers.first()
        val lower = copy.lowercase()
        assertTrue(
            listOf("couldn't", "could not").any { it in lower },
            "it says the read failed: $copy",
        )
        for (claim in listOf("nothing in", "no items", "empty", "yet", "everything is filed")) {
            assertFalse(claim in lower, "a failed read may not claim an absence: $copy")
        }
        for (scope in scopes) {
            assertFalse(
                GalleryFormat.emptyListing(scope, unreadable = false) == copy,
                "and it is not one of the ordinary answers: $copy",
            )
        }
    }

    /**
     * The album grid's teaching copy is **withheld** rather than replaced when the listing failed.
     *
     * Unlike the item grid, that screen still has something true on it — the Unfiled entry leads
     * somewhere whether or not the albums could be read — and the banner directly above already says
     * the read failed. A second line saying so would be the same sentence twice; "No albums yet" would
     * be an absence nobody established.
     */
    @Test
    fun an_unreadable_album_listing_says_nothing_rather_than_claiming_there_are_none() {
        assertNull(
            GalleryFormat.noAlbums(unreadable = true),
            "the banner above has already said the only thing that is known",
        )
        assertNotNull(
            GalleryFormat.noAlbums(unreadable = false),
            "but a vault that genuinely has no albums is still taught what they are for",
        )
    }

    /**
     * The no-albums copy has to teach two things: what an album is for, and what it is **not**.
     * Everything in the vault is encrypted either way, and a user who reads "album" as "extra lock"
     * has been misled by omission — the one place this screen could overstate what the app does.
     */
    @Test
    fun the_no_albums_copy_explains_grouping_and_does_not_promise_protection() {
        val copy = assertNotNull(GalleryFormat.noAlbums(unreadable = false))
        val lower = copy.lowercase()

        assertTrue("album" in lower, "it names the thing: $copy")
        assertTrue(
            listOf("group", "find").any { it in lower },
            "and says what one is for, since a blank grid teaches nothing: $copy",
        )
        assertTrue(
            "encrypted either way" in lower,
            "and that grouping is not what protects the files: $copy",
        )
        for (claim in listOf("lock", "protect", "secure", "password", "safer")) {
            assertFalse(claim in lower, "an album is not a $claim: $copy")
        }
    }

    /**
     * The two draft problems read as what they are: one is something to finish, the other is
     * something that did not happen.
     *
     * The failure line has to say **nothing was created**, because that is the question someone
     * staring at a dialog that did not close actually has — and it is true: the vault seals the name
     * before it writes the row, so a refused create leaves no half-made album in the grid to hunt for.
     * Neither line may read as destruction, since nothing here destroys anything.
     */
    @Test
    fun the_draft_problems_say_what_the_state_of_the_world_is() {
        val missing = GalleryFormat.albumDraftProblem(AlbumDraftProblem.NameMissing)
        val failed = GalleryFormat.albumDraftProblem(AlbumDraftProblem.CreateFailed)

        assertTrue("name" in missing.lowercase(), "it names what is missing: $missing")
        for (blame in listOf("error", "failed", "invalid", "wrong")) {
            assertFalse(blame in missing.lowercase(), "an unfinished field is not a fault: $missing")
        }

        assertTrue(
            "nothing was created" in failed.lowercase(),
            "the user needs to know there is no half-made album: $failed",
        )
        for (claim in listOf("deleted", "lost", "removed", "corrupt")) {
            assertFalse(claim in failed.lowercase(), "a refused create destroys nothing: $failed")
        }

        // A refused rename is a different statement of fact: the album is still there, under the name
        // it had. "Nothing was created" would be true and useless; this has to name the old name.
        val renameFailed = GalleryFormat.albumDraftProblem(AlbumDraftProblem.RenameFailed).lowercase()
        assertTrue("old name" in renameFailed, "it says what the album answers to now: $renameFailed")
        assertFalse("nothing was created" in renameFailed, "nothing was being created: $renameFailed")
    }

    /**
     * **The filing actions are moves, and the words have to say so.**
     *
     * An item is in at most one album, so filing something already filed takes it out of where it was.
     * "Add to album" would imply a second copy — of an encrypted file, in a vault — which is a claim
     * about what the user possesses, and the one this surface could most easily make by accident.
     *
     * The other direction must not read as deletion: that button sits where a shred button will
     * eventually sit, on a screen full of selected files, so "remove" is avoided for being what every
     * other app's delete button says.
     */
    @Test
    fun the_filing_actions_read_as_moves_and_not_as_copies_or_deletions() {
        val into = GalleryFormat.moveToAlbum().lowercase()
        assertTrue("move" in into, "it is a move: $into")
        assertFalse("add" in into, "'add' implies a second copy: $into")
        assertFalse("copy" in into, into)

        val out = GalleryFormat.unfileSelection().lowercase()
        for (claim in listOf("delete", "remove", "shred", "erase")) {
            assertFalse(claim in out, "taking an item out of an album destroys nothing: $out")
        }
        assertTrue("album" in out, "it says what it takes them out of: $out")
    }

    /**
     * The chooser explains the one-album rule, because that is what makes this a move rather than an
     * addition — and it is the only place the user is told.
     */
    @Test
    fun the_chooser_explains_that_an_item_is_in_one_album() {
        val copy = GalleryFormat.chooserExplanation(3).lowercase()

        assertTrue("3 items" in copy, "it says how many are moving: $copy")
        assertTrue("one album" in copy, "and the rule that makes this a move: $copy")
        assertTrue("leaves" in copy, "and what happens to anything already filed: $copy")
        assertFalse("copy" in copy, copy)

        assertTrue("this item" in GalleryFormat.chooserExplanation(1).lowercase(), "not '1 items'")
    }

    /**
     * The move's report follows the import's deleted-originals wording rather than inventing a third
     * style for the same shape of news: "4 of 5" when some did not make it, and the reason stated rather
     * than guessed at.
     */
    @Test
    fun the_move_report_says_how_many_actually_moved() {
        val partial = GalleryFormat.movedCount(4, 5, FilingDestination.Album).lowercase()
        assertTrue("4 of 5" in partial, "both numbers, or a half-move reads as a clean one: $partial")
        assertTrue("no longer in the vault" in partial, "and why the rest did not: $partial")

        assertTrue("1 item" in GalleryFormat.movedCount(1, 1, FilingDestination.Album), "not '1 items'")
        assertTrue(
            "nothing" in GalleryFormat.movedCount(0, 2, FilingDestination.Album).lowercase(),
            "zero reads as nothing happening, not as a count",
        )

        // The out-of-an-album wording stays clear of deletion here too, where it is reporting a
        // completed action and a user skimming would most easily misread it.
        val unfiled = GalleryFormat.movedCount(2, 2, FilingDestination.Unfiled).lowercase()
        assertTrue("out of their album" in unfiled, unfiled)
        for (claim in listOf("deleted", "removed", "gone")) {
            assertFalse(claim in unfiled, "nothing was destroyed: $unfiled")
        }
    }

    /**
     * The delete confirmation is the one piece of copy on the album surface that has to **correct** an
     * expectation rather than confirm one.
     *
     * Folder semantics say: delete the folder, lose what is in it. The opposite happens — an album is a
     * label, so every file survives and becomes unfiled. So the copy has to say the items stay *and*
     * where they go, and must never imply deletion of files, freeing of space, or permanence of
     * anything but the album.
     */
    @Test
    fun the_delete_confirmation_corrects_the_folder_expectation() {
        for (count in listOf(1, 2, 37)) {
            val copy = GalleryFormat.deleteAlbumExplanation(count)
            val lower = copy.lowercase()

            assertTrue("stay" in lower, "it says the items are kept: $copy")
            assertTrue("unfiled" in lower, "and where they go, or they look lost: $copy")
            assertTrue("only the album" in lower, "and what is actually deleted: $copy")
            for (claim in listOf("space", "free up", "permanent", "forever", "can't be undone")) {
                assertFalse(claim in lower, "deleting an album is none of that: $copy")
            }
        }

        assertTrue("37 items" in GalleryFormat.deleteAlbumExplanation(37), "it says how many")
        assertFalse("1 items" in GalleryFormat.deleteAlbumExplanation(1), "not '1 items'")
    }

    /**
     * An empty album gets the short version. There is nothing in it to reassure anyone about, and copy
     * insisting on safety where no risk exists reads as a warning.
     */
    @Test
    fun an_empty_album_is_not_reassured_about_its_contents() {
        val copy = GalleryFormat.deleteAlbumExplanation(0).lowercase()

        assertTrue("empty" in copy, copy)
        assertFalse("unfiled" in copy, "nothing to move, so nothing to say about where: $copy")
    }

    /**
     * **A count we do not have yet is not a count of zero.** The album heading appears as soon as the
     * album itself is known, which is independent of its item listing's first emission — so Delete
     * album is reachable while the item list is still empty for want of having arrived. Passing that
     * emptiness through would tell someone holding five hundred filed files that the album is empty,
     * in the one dialog whose whole job is to be exactly right about what is at stake.
     *
     * The unknown answer states the rule without a number: true of an album with one item and of one
     * with a thousand, and it cannot be read as "empty".
     */
    @Test
    fun an_album_whose_contents_are_not_known_yet_is_not_called_empty() {
        val copy = GalleryFormat.deleteAlbumExplanation(null)
        val lower = copy.lowercase()

        assertFalse("empty" in lower, "we have not read the listing; we cannot say that: $copy")
        assertTrue("stay" in lower, "it still says the items are kept: $copy")
        assertTrue("unfiled" in lower, "and where they go: $copy")
        assertTrue("only the album" in lower, "and what is actually deleted: $copy")
        assertFalse(
            Regex("""\d""").containsMatchIn(copy),
            "and it names no number, because it does not have one: $copy",
        )
        assertFalse(
            copy == GalleryFormat.deleteAlbumExplanation(0),
            "an unknown count is not the empty answer: $copy",
        )
    }

    /**
     * A failed delete must **not** claim nothing changed.
     *
     * The vault unfiles the items and then deletes the album row, with no transaction spanning the two,
     * so "nothing changed" might be false: interrupted between them, the items are already unfiled. The
     * copy says the one thing that is certainly still true — every file is still in the vault — and
     * admits the rest.
     */
    @Test
    fun a_failed_delete_does_not_promise_that_nothing_changed() {
        val copy = GalleryFormat.deleteAlbumFailed().lowercase()

        assertFalse("nothing was changed" in copy, "it cannot promise that: $copy")
        assertFalse("nothing changed" in copy, "it cannot promise that: $copy")
        assertTrue("still in your vault" in copy, "it says what is certainly true: $copy")
        assertTrue("may already" in copy, "and admits what it cannot be sure of: $copy")
    }

    /**
     * An album that has disappeared says where its contents went, for the same reason the confirmation
     * does: the grid the user is looking at has just emptied, which is what losing them would look like.
     */
    @Test
    fun the_missing_album_notice_says_where_the_items_went() {
        val copy = GalleryFormat.albumGone().lowercase()

        assertTrue("unfiled" in copy, copy)
        for (claim in listOf("deleted", "lost", "gone for good")) {
            assertFalse(claim in copy, "the files are none of those: $copy")
        }
        assertTrue("album" in GalleryFormat.backToAlbums().lowercase(), "the way out says where it leads")
    }

    /** The affordance and the dialog it opens use one name for one thing. */
    @Test
    fun the_create_affordance_and_its_dialog_agree() {
        assertTrue("album" in GalleryFormat.newAlbum().lowercase(), GalleryFormat.newAlbum())
        assertTrue("album" in GalleryFormat.albumNameLabel().lowercase(), GalleryFormat.albumNameLabel())
    }

    /**
     * An album tile's caption reads sensibly at zero and one, and "Empty" rather than "0 items" —
     * a freshly created album is the ordinary state of one, so it should read as a fact rather than
     * a fault.
     */
    @Test
    fun an_album_subtitle_counts_items_and_names_the_empty_case() {
        assertEquals("Empty", GalleryFormat.albumSubtitle(0))
        assertEquals("1 item", GalleryFormat.albumSubtitle(1), "not '1 items'")
        assertEquals("12 items", GalleryFormat.albumSubtitle(12))
    }

    /**
     * **An album is never a lock.** No padlock, no "protected", nothing implying a security boundary
     * that does not exist: per-album keys are not in this app, and a caption suggesting otherwise is
     * the easiest way to break the honesty rule by accident. Checked as a rule rather than trusted
     * to review, because this caption is the one a later change is most likely to embellish.
     */
    @Test
    fun an_album_subtitle_never_claims_the_album_protects_anything() {
        val captions = listOf(0, 1, 2, 99).map { GalleryFormat.albumSubtitle(it).lowercase() }

        for (caption in captions) {
            for (claim in listOf("lock", "protect", "secure", "private", "encrypt", "safe", "hidden")) {
                assertFalse(claim in caption, "an album organises; it does not $claim: $caption")
            }
        }
    }

    /**
     * The selection count has to read sensibly at zero, which is a state the user can reach without
     * asking for it: a selected item can leave the listing — shredded, or moved out of the album
     * being viewed — and the mode stays. "0 selected" reads like a malfunction where "None selected"
     * reads like a fact.
     */
    @Test
    fun the_selected_count_reads_sensibly_at_zero_and_one() {
        assertEquals("None selected", GalleryFormat.selectedCount(0))
        assertEquals("1 selected", GalleryFormat.selectedCount(1), "not '1 items'")
        assertEquals("7 selected", GalleryFormat.selectedCount(7))
    }

    /** Each named stage says something different, or the label is decoration rather than information. */
    @Test
    fun the_named_stages_are_told_apart() {
        val labels = listOf(ImportStage.FETCHING, ImportStage.COPYING, ImportStage.FINISHING)
            .map { GalleryFormat.stageLabel(it) }

        assertEquals(labels.size, labels.distinct().size, "two stages reading alike explain nothing: $labels")
    }
}
