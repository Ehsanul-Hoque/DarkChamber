package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerNotice
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackNote
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import dev.ehsanfahad.darkchamber.feature.vault.api.label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the viewer says, tested from what each line must **not** claim — the only way a rewrite of
 * the wording cannot quietly bring an overclaim back.
 */
class ViewerFormatTest {
    private val everyMessagePage: List<ViewerPage> = listOf(
        ViewerPage.NotViewable("PDF"),
        ViewerPage.TooLarge,
        ViewerPage.CantShow,
        ViewerPage.CantPlay,
        ViewerPage.PasswordProtected,
        ViewerPage.Unreadable,
        ViewerPage.Unverified,
        ViewerPage.StoppedUnverified,
        ViewerPage.NeedsUpdate,
        ViewerPage.CouldntOpen,
    )

    private fun message(page: ViewerPage): String =
        assertNotNull(ViewerFormat.pageMessage(page), "a message for $page").also {
            assertTrue(it.isNotBlank(), "the message for $page says something")
        }

    private fun assertSaysNone(text: String, vararg words: String) {
        for (word in words) assertFalse(text.contains(word, ignoreCase = true), "\"$text\" must not say \"$word\"")
    }

    @Test
    fun every_page_but_the_picture_and_the_wait_says_something() {
        everyMessagePage.forEach { message(it) }
        assertNull(ViewerFormat.pageMessage(ViewerPage.Picture))
        assertNull(ViewerFormat.pageMessage(ViewerPage.Loading))
    }

    /**
     * Nothing was read for this page, so nothing is known about the bytes: it may not call the file
     * "safe" or "intact". Nor may it promise when a viewer arrives.
     */
    @Test
    fun a_kind_with_no_viewer_yet_promises_nothing_about_the_file_or_the_future() {
        val text = message(ViewerPage.NotViewable("PDF"))
        assertTrue(text.contains("can't be opened here", ignoreCase = true), text)
        assertSaysNone(text, "safe", "intact", "protected", "soon", "later", "update", "yet to")
    }

    /**
     * A decoder that declines an intact file is not damage, and a file too big to hold is not
     * either: neither page may sound like something is wrong with the file.
     */
    @Test
    fun the_pages_for_a_working_file_never_sound_like_damage() {
        for (
            page in listOf(
                ViewerPage.NotViewable("PDF"), ViewerPage.TooLarge, ViewerPage.CantShow, ViewerPage.CantPlay,
                ViewerPage.PasswordProtected, ViewerPage.Unreadable, ViewerPage.NeedsUpdate,
            )
        ) {
            assertSaysNone(message(page), "damage", "corrupt", "tamper", "verif", "integrity", "broken")
        }
    }

    /**
     * A PDF locked with a password: the lock is the reason, and it is one this app cannot open — there
     * is nowhere to type it. Nothing about the file's state, and no promise that it can be opened later.
     */
    @Test
    fun a_password_protected_pdf_says_the_password_cannot_be_entered_here() {
        val text = message(ViewerPage.PasswordProtected)
        assertTrue(text.contains("password", ignoreCase = true), text)
        assertTrue(text.contains("can't be entered here", ignoreCase = true), text)
        assertSaysNone(text, "wrong", "incorrect", "safe", "secure", "encrypted", "soon", "later", "update")
    }

    /**
     * A PDF the renderer will not read was still verified by the vault: it is exactly the file that was
     * added. So the line may say it is unchanged — that much is known — and puts the limit on reading
     * it here, naming no cause (malformed? a feature the renderer refuses?) it was never told.
     */
    @Test
    fun an_unreadable_pdf_says_it_is_unchanged_and_names_no_cause() {
        val text = message(ViewerPage.Unreadable)
        assertTrue(text.contains("can't be read here", ignoreCase = true), text)
        assertTrue(text.contains("unchanged", ignoreCase = true), text)
        assertSaysNone(text, "because", "format", "malformed", "invalid", "unsupported", "safe", "intact", "this device")
    }

    /**
     * A failed integrity check says what is known — it could not be verified, and so none of it is
     * shown — and that the file may have changed. It never names a cause it cannot know.
     */
    @Test
    fun an_unverified_file_says_what_is_known_and_names_no_cause() {
        val text = message(ViewerPage.Unverified)
        assertTrue(text.contains("couldn't be verified", ignoreCase = true), text)
        assertTrue(text.contains("none of it is shown", ignoreCase = true), text)
        assertSaysNone(text, "tamper", "corrupt", "attack", "hack", "safe", "damaged")
    }

    /**
     * A clip that failed its check partway through playing: some of it was shown — every part that
     * played was verified — so it must not say none of it was; it says playback stopped and why it
     * knows, and, like the page above, names no cause and never calls what played safe.
     */
    @Test
    fun a_clip_stopped_partway_says_playback_stopped_not_that_none_was_shown() {
        val text = message(ViewerPage.StoppedUnverified)
        assertTrue(text.contains("couldn't be verified", ignoreCase = true), text)
        assertTrue(text.contains("playback stopped", ignoreCase = true), text)
        assertTrue(text.contains("may have changed", ignoreCase = true), text)
        assertSaysNone(text, "none of it", "tamper", "corrupt", "attack", "hack", "safe", "damaged")
    }

    /**
     * No decoder here plays it: the limit is the device, and the line says so. It does not blame the
     * file or the vault, and it does not name a cause (a format, a codec) it was never told.
     */
    @Test
    fun a_file_this_device_cannot_play_puts_the_limit_on_the_device() {
        val text = message(ViewerPage.CantPlay)
        assertTrue(text.contains("this device can't play", ignoreCase = true), text)
        assertSaysNone(text, "vault", "DarkChamber", "format", "codec", "unsupported", "because", "safe", "intact")
    }

    /**
     * A note on a page that plays in part says what is missing, and that the device is the limit — never
     * that the file is damaged, and never a cause it does not know.
     */
    @Test
    fun a_note_says_which_part_is_missing_and_puts_the_limit_on_the_device() {
        val noPicture = ViewerFormat.trackNote(TrackNote.NoPicture)
        val noSound = ViewerFormat.trackNote(TrackNote.NoSound)
        assertEquals("Video can't be shown on this device", noPicture)
        assertEquals("No sound — this device can't play this audio", noSound)
        for (text in listOf(noPicture, noSound)) {
            assertSaysNone(text, "damage", "corrupt", "tamper", "verif", "integrity", "broken", "vault", "format", "codec")
        }
    }

    @Test
    fun a_file_from_a_newer_app_says_to_update_it() {
        assertTrue(message(ViewerPage.NeedsUpdate).contains("update", ignoreCase = true))
    }

    /** "Couldn't be opened" — and why is not known, so no why. */
    @Test
    fun a_file_that_would_not_open_names_no_cause() {
        assertSaysNone(message(ViewerPage.CouldntOpen), "because", "damage", "corrupt", "tamper", "missing", "deleted", "space")
    }

    // --- the bar ----------------------------------------------------------------------------------

    @Test
    fun the_bar_names_the_item_or_says_its_name_cant_be_verified() {
        assertEquals("beach.jpg", ViewerFormat.title(GalleryItemMeta.Readable("beach.jpg", "image/jpeg")))
        assertEquals(GalleryItemMeta.Unreadable.label(), ViewerFormat.title(GalleryItemMeta.Unreadable), "the grid's own wording")
    }

    /** One-based, the way a person counts; the pager is zero-based. */
    @Test
    fun the_position_counts_from_one() {
        assertEquals("1 of 1", ViewerFormat.position(index = 0, count = 1))
        assertEquals("12 of 340", ViewerFormat.position(index = 11, count = 340))
    }

    // --- details ----------------------------------------------------------------------------------

    private val now = 1_800_000_000_000L

    private fun item(meta: GalleryItemMeta, size: Long = 3_565_158, createdAt: Long = now - 3 * 86_400_000L) =
        GalleryItem(id = byteArrayOf(1), kind = VaultItemKind.FILE, size = size, createdAt = createdAt, meta = meta)

    private fun rows(item: GalleryItem, album: AlbumTileName?) =
        ViewerFormat.details(item, album, now).associate { it.label to it.value }

    @Test
    fun details_list_name_type_size_age_and_album() {
        val rows = rows(item(GalleryItemMeta.Readable("beach.jpg", "image/jpeg")), AlbumTileName.Readable("Trip"))

        assertEquals(listOf("Name", "Type", "Size", "Added", "Album"), rows.keys.toList(), "in this order")
        assertEquals("beach.jpg", rows["Name"])
        assertEquals("Image · JPEG", rows["Type"])
        assertEquals(GalleryFormat.size(3_565_158), rows["Size"], "the gallery's own size format")
        assertEquals(GalleryFormat.age(now - 3 * 86_400_000L, now), rows["Added"], "the gallery's coarse age, on purpose")
        assertEquals("Trip", rows["Album"])
    }

    @Test
    fun the_type_reads_as_a_family_and_a_format() {
        fun type(mime: String) = rows(item(GalleryItemMeta.Readable("x", mime)), null)["Type"]
        assertEquals("Video · MP4", type("video/mp4"))
        assertEquals("Audio · MPEG", type("audio/mpeg"))
        assertEquals("PDF", type("application/pdf"))
        assertEquals("Text · PLAIN", type("text/plain"))
        assertEquals("File · ZIP", type("application/zip"))
        assertEquals("File", type("application/octet-stream"), "an unspecific type says no more than it knows")
    }

    @Test
    fun an_unfiled_item_says_so() {
        assertEquals("Not in an album", rows(item(GalleryItemMeta.Readable("x", "image/png")), album = null)["Album"])
    }

    /** Unverifiable details are said once per field, in the grid's own words — never guessed. */
    @Test
    fun details_that_will_not_open_say_so_and_guess_nothing() {
        val unverifiable = GalleryItemMeta.Unreadable.label()
        val rows = rows(item(GalleryItemMeta.Unreadable), AlbumTileName.Unreadable)

        assertEquals(unverifiable, rows["Name"])
        assertEquals(unverifiable, rows["Type"])
        assertEquals(unverifiable, rows["Album"], "an album whose name will not open reads the same way")
        assertEquals(GalleryFormat.size(3_565_158), rows["Size"], "the plaintext columns are still known")
    }

    /**
     * The size is what the user handed over, not what the vault occupies (the container adds a header
     * and a tag per chunk), so it is labelled "Size" and never anything about space or storage.
     */
    @Test
    fun the_size_is_never_called_space_used() {
        val rows = ViewerFormat.details(item(GalleryItemMeta.Readable("x", "image/png")), null, now)
        for (row in rows) assertSaysNone(row.label, "space", "storage", "disk", "used")
    }

    // --- delete ------------------------------------------------------------------------------------

    @Test
    fun the_delete_question_counts_what_it_will_delete() {
        assertEquals("Delete this file?", ViewerFormat.deleteQuestion(1))
        assertEquals("Delete 3 files?", ViewerFormat.deleteQuestion(3))
        assertEquals("Delete", ViewerFormat.deleteAction(1))
        assertEquals("Delete 3", ViewerFormat.deleteAction(3), "the button says the scale too")
    }

    /**
     * Permanent, and why: the key is destroyed, so the app can never open it again. That is what the
     * code guarantees. It must not claim more — the flash may keep old blocks, and the threat model
     * says so — so nothing about erasing, wiping, or what anyone else could do. Both forms, because an
     * absence test that reads one branch proves nothing about the other.
     */
    @Test
    fun the_delete_explanation_says_permanent_and_claims_no_more() {
        for (count in listOf(1, 4)) {
            val text = ViewerFormat.deleteExplanation(count)
            assertTrue(text.contains("key", ignoreCase = true), text)
            assertTrue(text.contains("never open", ignoreCase = true), text)
            assertSaysNone(
                text,
                "erase", "wipe", "anyone", "no one", "nobody", "unrecoverable", "forensic",
                "secure", "trace", "undo", "trash", "bin",
            )
        }
    }

    @Test
    fun the_result_counts_what_was_deleted() {
        assertEquals("1 file deleted.", ViewerFormat.deleted(1))
        assertEquals("5 files deleted.", ViewerFormat.deleted(5))
        assertEquals("Nothing was deleted.", ViewerFormat.deleted(0))
    }

    /**
     * A page with no picture still says what kind of file it is, as the grid's tile does — and a page
     * that failed its check, or whose details will not open, carries the grid's warning mark instead.
     */
    @Test
    fun a_page_without_a_picture_carries_the_grids_badge() {
        val jpeg = GalleryItemMeta.Readable("a.jpg", "image/jpeg")
        assertEquals("VID", ViewerFormat.pageBadge(ViewerPage.NotViewable("VID"), GalleryItemMeta.Readable("a.mp4", "video/mp4")))
        assertEquals(GalleryFormat.typeBadge("image/jpeg"), ViewerFormat.pageBadge(ViewerPage.TooLarge, jpeg))
        assertEquals(GalleryFormat.typeBadge("image/jpeg"), ViewerFormat.pageBadge(ViewerPage.CantShow, jpeg))
        assertEquals("!", ViewerFormat.pageBadge(ViewerPage.Unverified, jpeg))
        assertEquals("!", ViewerFormat.pageBadge(ViewerPage.StoppedUnverified, GalleryItemMeta.Readable("a.mp4", "video/mp4")))
        assertEquals("!", ViewerFormat.pageBadge(ViewerPage.CouldntOpen, GalleryItemMeta.Unreadable))
    }

    @Test
    fun the_dialogs_buttons_say_what_they_do() {
        assertEquals("Cancel", ViewerFormat.cancel())
        assertEquals("Deleting…", ViewerFormat.deleting())
    }

    /** The viewer deletes one file at a time, and says which way it went. */
    @Test
    fun the_viewers_notice_is_the_one_file_result_or_the_refusal() {
        assertEquals("1 file deleted.", ViewerFormat.notice(ViewerNotice.Deleted))
        assertEquals("Couldn't delete. Nothing was deleted.", ViewerFormat.notice(ViewerNotice.DeleteFailed))
    }

    /** A refused delete is all or nothing in the catalog, so "nothing was deleted" is true; why is unknown. */
    @Test
    fun a_failed_delete_says_nothing_was_deleted_and_names_no_cause() {
        val text = ViewerFormat.deleteFailed()
        assertTrue(text.contains("nothing was deleted", ignoreCase = true), text)
        assertSaysNone(text, "because", "space", "locked", "damage", "permission")
    }

    @Test
    fun the_bar_actions_are_named_for_what_they_do() {
        assertEquals("Back", ViewerFormat.back())
        assertEquals("Details", ViewerFormat.detailsAction())
        assertEquals("Delete", ViewerFormat.deleteAction(1))
    }

    /** Only an integrity failure takes the warning style — at open or mid-play; styling is a claim too. */
    @Test
    fun only_the_unverified_pages_are_warnings() {
        assertTrue(ViewerFormat.pageIsWarning(ViewerPage.Unverified))
        assertTrue(ViewerFormat.pageIsWarning(ViewerPage.StoppedUnverified))
        val integrity = setOf<ViewerPage>(ViewerPage.Unverified, ViewerPage.StoppedUnverified)
        for (page in everyMessagePage - integrity + ViewerPage.Picture + ViewerPage.Loading) {
            assertEquals(false, ViewerFormat.pageIsWarning(page), "for $page")
        }
    }

    // ---- the player ------------------------------------------------------------------------------

    @Test
    fun a_play_time_reads_like_a_clock() {
        assertEquals("0:00", ViewerFormat.playTime(0L))
        assertEquals("0:00", ViewerFormat.playTime(999L), "a second not yet reached is not shown")
        assertEquals("0:42", ViewerFormat.playTime(42_000L))
        assertEquals("1:51", ViewerFormat.playTime(111_500L))
        assertEquals("12:05", ViewerFormat.playTime(725_000L))
        assertEquals("59:59", ViewerFormat.playTime(3_599_000L))
        assertEquals("1:00:00", ViewerFormat.playTime(3_600_000L), "hours appear only when there are some")
        assertEquals("1:02:03", ViewerFormat.playTime(3_723_000L))
        assertEquals("0:00", ViewerFormat.playTime(-3_000L), "never a minus sign")
    }

    @Test
    fun the_times_under_the_seek_bar_show_the_total_once_it_is_known() {
        assertEquals("0:42 / 1:51", ViewerFormat.playTimes(42_000L, 111_000L))
        assertEquals("0:42", ViewerFormat.playTimes(42_000L, null))
    }

    @Test
    fun the_seek_bar_is_spoken_as_where_it_stands() {
        assertEquals("Position", ViewerFormat.seekLabel())
        assertEquals("0:42 of 1:51", ViewerFormat.seekState(42_000L, 111_000L))
        assertEquals("0:42", ViewerFormat.seekState(42_000L, null))
    }

    @Test
    fun a_speed_is_written_without_trailing_zeros() {
        assertEquals("0.5×", ViewerFormat.speed(0.5f))
        assertEquals("1×", ViewerFormat.speed(1f))
        assertEquals("1.25×", ViewerFormat.speed(1.25f))
        assertEquals("1.5×", ViewerFormat.speed(1.5f))
        assertEquals("2×", ViewerFormat.speed(2f))
        assertEquals("Speed, 1.25×", ViewerFormat.speedDescription(1.25f))
    }

    @Test
    fun the_centre_control_says_what_pressing_it_does() {
        assertEquals("Play", ViewerFormat.centreControl(CentreControl.Play))
        assertEquals("Pause", ViewerFormat.centreControl(CentreControl.Pause))
        assertEquals("Play again", ViewerFormat.centreControl(CentreControl.Replay))
    }

    @Test
    fun the_orientation_button_names_where_it_turns_the_screen() {
        assertEquals("Landscape", ViewerFormat.orientationLabel(ScreenOrientation.Landscape))
        assertEquals("Portrait", ViewerFormat.orientationLabel(ScreenOrientation.Portrait))
        assertEquals("Turn to landscape", ViewerFormat.orientationDescription(ScreenOrientation.Landscape))
        assertEquals("Turn to portrait", ViewerFormat.orientationDescription(ScreenOrientation.Portrait))
    }

    @Test
    fun a_screen_reader_can_show_and_hide_the_controls_from_the_picture() {
        assertEquals("Video", ViewerFormat.playerArea(PlayerKind.Video))
        assertEquals("Audio", ViewerFormat.playerArea(PlayerKind.Audio))
        assertEquals("Show controls", ViewerFormat.controlsAction(shown = false))
        assertEquals("Hide controls", ViewerFormat.controlsAction(shown = true))
        assertEquals("Back 10 seconds", ViewerFormat.skipAction(forward = false))
        assertEquals("Forward 10 seconds", ViewerFormat.skipAction(forward = true))
    }

    @Test
    fun a_gesture_shows_what_it_did() {
        assertEquals("+10 s", ViewerFormat.skipFeedback(forward = true))
        assertEquals("−10 s", ViewerFormat.skipFeedback(forward = false), "a true minus sign")
        assertEquals("60%", ViewerFormat.levelFeedback(0.6f))
        assertEquals("0%", ViewerFormat.levelFeedback(0f))
        assertEquals("100%", ViewerFormat.levelFeedback(1f))
        assertEquals("33%", ViewerFormat.levelFeedback(0.334f), "rounded")
    }
}
