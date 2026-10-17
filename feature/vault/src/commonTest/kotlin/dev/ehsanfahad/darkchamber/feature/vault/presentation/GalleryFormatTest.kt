package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraftProblem
import dev.ehsanfahad.darkchamber.feature.vault.api.FilingDestination
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
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
        assertTrue("out of the album" in unfiled, unfiled)
        for (claim in listOf("deleted", "removed", "gone")) {
            assertFalse(claim in unfiled, "nothing was destroyed: $unfiled")
        }
    }

    /**
     * **Every count branch has to read as English.** The destination phrase is shared by all of them, so
     * a possessive chosen for the plural turns the singular into "1 item taken out of their album" — which
     * is what shipped, because the only test of this wording used a plural count.
     *
     * "The album" rather than "its"/"their": taking items out is only ever offered from inside one album,
     * so there is exactly one album to refer to, and the same phrase is then correct at every count.
     */
    @Test
    fun the_move_report_agrees_with_its_own_count() {
        for (destination in FilingDestination.entries) {
            for ((moved, requested) in listOf(0 to 2, 1 to 1, 2 to 2, 4 to 5)) {
                val copy = GalleryFormat.movedCount(moved, requested, destination)
                val lower = copy.lowercase()
                assertFalse("their" in lower, "no possessive that only fits a plural: $copy")
                assertFalse("its album" in lower, "nor one that only fits a singular: $copy")
                if (moved == 1) {
                    assertFalse("1 items" in copy, "singular count, singular noun: $copy")
                    assertTrue("1 item " in copy, copy)
                }
            }
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

    /**
     * **The load-bearing sentence of the import card**, and the one that has to be said before
     * anything happens: a lock or a background ends the batch, and afterwards there is nobody left
     * to tell.
     */
    @Test
    fun the_keep_open_notice_warns_before_the_fact() {
        val copy = GalleryFormat.keepOpenNotice(ImportStage.ENCRYPTING).lowercase()

        assertTrue("keep" in copy, "it asks for the one thing that helps: $copy")
        assertTrue("stops the import" in copy, "and says what the cost is: $copy")
    }

    /**
     * **The clause the user is owed while a provider is still sending the file**: stopping ends the
     * import, but nothing can call the transfer back, so on a metered connection it is their data
     * being spent after they believe they have stopped (docs/ARCHITECTURE.md).
     */
    @Test
    fun a_fetch_admits_that_the_download_carries_on() {
        val waiting = GalleryFormat.keepOpenNotice(ImportStage.FETCHING).lowercase()

        assertTrue("download" in waiting, "it names what carries on: $waiting")
        assertTrue("keep" in waiting, "without losing the warning it is appended to: $waiting")
    }

    @Test
    fun stopping_during_a_fetch_says_the_file_is_still_coming() {
        val stopping = GalleryFormat.stoppingNotice(ImportStage.FETCHING).lowercase()

        assertTrue("download" in stopping, "it says the transfer is still running: $stopping")
        assertTrue("cannot be cancelled" in stopping, "and that nothing can call it back: $stopping")
        assertTrue("data" in stopping, "which is the cost that makes it worth saying: $stopping")
    }

    /**
     * **And it is said only while it is true**, which is the whole reason the stage is surfaced.
     *
     * Each stage asserted on its own rather than as a group: a group assertion passes while one
     * member is wrong, and these three are wrong for different reasons — a stop during the encrypt
     * leaves no transfer running at all, and a streaming provider's transfer ends when the stream
     * closes.
     */
    @Test
    fun no_other_stage_claims_a_download_is_running() {
        // Derived from the enum rather than listed: a stage added later has to answer this question
        // instead of being quietly exempt from it.
        for (stage in ImportStage.entries.filter { it != ImportStage.FETCHING }) {
            assertFalse(
                "download" in GalleryFormat.keepOpenNotice(stage).lowercase(),
                "$stage has nothing downloading: ${GalleryFormat.keepOpenNotice(stage)}",
            )
            assertFalse(
                "download" in GalleryFormat.stoppingNotice(stage).lowercase(),
                "$stage has nothing to call back: ${GalleryFormat.stoppingNotice(stage)}",
            )
        }
    }

    @Test
    fun a_batch_with_no_stage_yet_claims_nothing_either() {
        // The picker is still open, or the batch has not reached a file: there is no transfer to
        // describe, and the card that shows this has no stage to read.
        assertFalse("download" in GalleryFormat.keepOpenNotice(null).lowercase())
        assertFalse("download" in GalleryFormat.stoppingNotice(null).lowercase())
    }

    /**
     * The stopping notice keeps the half that stops a stop feeling like a loss, and still promises
     * neither an instant stop nor a slow one — which wait it is in depends on where the stop lands.
     */
    @Test
    fun stopping_still_says_what_is_already_safe() {
        val copy = GalleryFormat.stoppingNotice(ImportStage.ENCRYPTING).lowercase()

        assertTrue("stays in the vault" in copy, "nothing already added is at risk: $copy")
        assertFalse("immediately" in copy, "no promise of an instant stop: $copy")
    }

    /** Each named stage says something different, or the label is decoration rather than information. */
    @Test
    fun the_named_stages_are_told_apart() {
        val labels = listOf(ImportStage.FETCHING, ImportStage.COPYING, ImportStage.FINISHING)
            .map { GalleryFormat.stageLabel(it) }

        assertEquals(labels.size, labels.distinct().size, "two stages reading alike explain nothing: $labels")
    }

    /**
     * The collapsed control names the order the grid is in, so every order needs a label of its own —
     * two orders reading alike would make the control say nothing while appearing to.
     */
    @Test
    fun every_order_has_a_label_of_its_own() {
        val labels = GalleryOrder.entries.map { GalleryFormat.orderLabel(it) }

        assertEquals(labels.size, labels.distinct().size, "two orders reading alike: $labels")
        assertTrue(labels.none { it.isBlank() }, "an unlabelled order cannot be chosen: $labels")
        assertEquals("Newest", GalleryFormat.orderLabel(GalleryOrder.Newest))
        assertEquals("Oldest", GalleryFormat.orderLabel(GalleryOrder.Oldest))
        assertEquals("A–Z", GalleryFormat.orderLabel(GalleryOrder.NameAsc))
        assertEquals("Z–A", GalleryFormat.orderLabel(GalleryOrder.NameDesc))
    }

    /**
     * **A search that matched nothing is not a listing with nothing in it**, and the grid is empty in
     * both cases — the same shape of confusion the unreadable answer exists for.
     *
     * Every per-scope answer asserts something about what is in the vault, and all three would be
     * false here: a search for "tax" inside a full album must not say the album is empty, and on the
     * whole vault it must not say the vault is. So this answer talks about the search.
     */
    @Test
    fun a_search_that_matched_nothing_claims_no_absence() {
        val scopes = listOf(GalleryScope.WholeVault, GalleryScope.Album, GalleryScope.Unfiled)

        val answers = scopes.map { GalleryFormat.emptyListing(it, unreadable = false, searching = true) }
        assertEquals(1, answers.toSet().size, "the query is what came up empty; the scope adds nothing")

        val copy = answers.first()
        val lower = copy.lowercase()
        assertTrue("match" in lower, "it says what did not happen — nothing matched: $copy")
        for (claim in listOf("nothing in", "no items", "empty", "yet", "everything is filed")) {
            assertFalse(claim in lower, "a search that found nothing may not claim an absence: $copy")
        }
        for (scope in scopes) {
            assertFalse(
                GalleryFormat.emptyListing(scope, unreadable = false) == copy,
                "and it is not one of the ordinary answers: $copy",
            )
        }
    }

    /**
     * It also says **what** was searched, because the two ways to get no results that are not the
     * user's mistake are both invisible otherwise: only names are matched (so "pdf" finds nothing),
     * and only this screen is searched (so an album's grid cannot answer for the vault).
     */
    @Test
    fun the_empty_search_answer_says_what_was_searched() {
        val copy = GalleryFormat.emptyListing(GalleryScope.Album, unreadable = false, searching = true)
        val lower = copy.lowercase()

        assertTrue("name" in lower, "names are what a query is matched against: $copy")
        // Both clauses are load-bearing, and dropping either leaves a sentence that still reads fine:
        // without this one, an album's empty result looks like an answer about the whole vault.
        assertTrue("screen" in lower, "and this screen is all that was searched: $copy")
    }

    /**
     * Precedence, and it only goes one way: a catalog that could not be read must not be described as
     * a search that matched nothing. The query is still in the field when the read fails, so both
     * conditions are true at once and the copy has to pick — "nothing matches" would blame the user's
     * typing for a vault that did not open.
     */
    @Test
    fun a_failed_read_outranks_a_running_search() {
        for (scope in listOf(GalleryScope.WholeVault, GalleryScope.Album, GalleryScope.Unfiled)) {
            assertEquals(
                GalleryFormat.emptyListing(scope, unreadable = true),
                GalleryFormat.emptyListing(scope, unreadable = true, searching = true),
                "a failed read is the reason the grid is empty, whatever is typed",
            )
        }
    }

    /**
     * Nothing to say, said as nothing — the same shape as `noAlbums`, and for the same reason: an
     * empty string would still occupy a line and make the caller's layout decide what this layer
     * already knows.
     */
    @Test
    fun nothing_is_said_when_a_search_left_nothing_out() {
        assertNull(GalleryFormat.hiddenUnreadable(0))
    }

    /**
     * Items whose names will not open are excluded from results — a query cannot match a name nobody
     * can read — and the exclusion is **stated**, because a search that silently drops them is a way
     * to make tampering invisible (docs/THREAT_MODEL.md).
     */
    @Test
    fun what_a_search_left_out_is_counted_and_reads_as_english() {
        assertEquals(
            "1 item couldn't be read, so it isn't in these results.",
            GalleryFormat.hiddenUnreadable(1),
        )
        assertEquals(
            "4 items couldn't be read, so they aren't in these results.",
            GalleryFormat.hiddenUnreadable(4),
        )
    }

    /**
     * And it does not say **why**. Corruption and tampering are indistinguishable from here — a GCM
     * tag mismatch is all that is known — so naming either one is a guess presented as a finding
     * (hard rule 6).
     */
    @Test
    fun what_a_search_left_out_is_not_explained() {
        // Both branches, because an absence asserted against one of two wordings is an absence
        // asserted against half the code — the singular line can name a cause the plural one does not.
        for (count in listOf(1, 2)) {
            val copy = assertNotNull(GalleryFormat.hiddenUnreadable(count)).lowercase()

            for (guess in listOf("corrupt", "tamper", "damaged", "modified", "attack", "deleted", "lost")) {
                assertFalse(guess in copy, "the line names a cause we cannot know: $copy")
            }
        }
    }

    /**
     * The field says what it searches, where a plain "Search" would imply everything on the tile is
     * fair game — the user typing "pdf" or a date is the failure this label is placed to prevent.
     */
    @Test
    fun the_search_field_says_it_searches_names() {
        assertTrue("name" in GalleryFormat.searchNames().lowercase(), GalleryFormat.searchNames())
    }

    /**
     * The tile's album badge draws a mark and a name. Seen together they say "this is in an album
     * called Trip"; announced, the name alone is a stray word between a file name and a size — so
     * the mark is what carries the relationship.
     */
    @Test
    fun the_album_mark_on_a_tile_is_named_for_a_reader() {
        val spoken = GalleryFormat.inAlbum().lowercase()

        assertTrue("album" in spoken, "it names what the mark means: $spoken")
        assertTrue("in" in spoken, "and that the item is in one: $spoken")
    }

    /**
     * Spoken, the order control has to say what it is. On screen it reads "Newest" beside a
     * chevron, which is a control, a current value and an invitation all at once; announced as
     * "Newest" it is none of them. The chevron itself stays silent for exactly this reason — this
     * description is where that information lives.
     */
    /**
     * The album overflow is a drawing with no label beside it, so its description is the only thing
     * a screen reader has to go on. It has to name the **subject** — three dots are nothing, and
     * "more" alone does not say more of what.
     */
    @Test
    fun the_album_overflow_names_what_its_menu_acts_on() {
        val spoken = GalleryFormat.albumActions()

        assertTrue("album" in spoken.lowercase(), "it says what the menu acts on: $spoken")
        assertFalse("dots" in spoken.lowercase(), "and never describes the glyph: $spoken")
        // The album's own name is deliberately absent: the bar above already says which album this
        // screen is, and a decrypted name does not need announcing twice.
        assertEquals("More actions for this album", spoken)
    }

    @Test
    fun the_order_control_is_named_as_well_as_shown() {
        for (order in GalleryOrder.entries) {
            val spoken = GalleryFormat.orderControlDescription(order)

            assertTrue("sort" in spoken.lowercase(), "it says what it controls: $spoken")
            assertTrue(GalleryFormat.orderLabel(order) in spoken, "and which order is on: $spoken")
        }
    }

    /**
     * The offer describes the **screen**, which is knowable, and never how much is hidden, which is
     * not: a finished batch carries counts and no ids, so nobody here can say whether a filter is
     * keeping back one of the new items, all of them or none.
     */
    @Test
    fun the_import_offer_claims_no_number() {
        for (offer in ImportOffer.entries) {
            val copy = GalleryFormat.importOfferExplanation(offer)

            assertTrue("may" in copy, "it says what might be the case, not what is: $copy")
            for (digit in '0'..'9') {
                assertFalse(digit in copy, "a count nobody has: $copy")
            }
        }
    }

    /** Two different problems — one hides an item, the other moves it — so not one sentence. */
    @Test
    fun the_two_offers_do_not_share_their_wording() {
        val explanations = ImportOffer.entries.map { GalleryFormat.importOfferExplanation(it) }
        val actions = ImportOffer.entries.map { GalleryFormat.importOfferAction(it) }

        assertEquals(explanations.size, explanations.distinct().size, "$explanations")
        assertEquals(actions.size, actions.distinct().size, "$actions")
    }

    /**
     * Clearing the search from the summary card is the **same act** the search control performs, so
     * it says the same words — a second phrasing would read as a second, different thing.
     */
    @Test
    fun clearing_the_search_is_worded_the_same_wherever_it_is_offered() {
        assertEquals(GalleryFormat.clearSearch(), GalleryFormat.importOfferAction(ImportOffer.ClearSearch))
    }

    /**
     * Leaving the search **discards the query**, and the label has to carry that. "Done" would read
     * as keeping the filter, which is the opposite of what happens.
     */
    @Test
    fun leaving_the_search_says_the_query_goes() {
        val copy = GalleryFormat.clearSearch()

        assertTrue("clear" in copy.lowercase(), "it says the query is cleared: $copy")
        assertFalse("done" in copy.lowercase(), "and not that the search is simply finished: $copy")
    }
}
