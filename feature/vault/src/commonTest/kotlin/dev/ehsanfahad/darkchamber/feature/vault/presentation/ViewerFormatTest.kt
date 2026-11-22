package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerNotice
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackNote
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPageSize
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import dev.ehsanfahad.darkchamber.feature.vault.api.label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    // ---- a PDF's pages ------------------------------------------------------------------------------

    /** Pages are counted from 1 on screen, from 0 in code. */
    @Test
    fun a_pdf_page_is_named_from_one_with_the_count() {
        assertEquals("Page 1 of 12", ViewerFormat.pdfPage(index = 0, count = 12))
        assertEquals("Page 12 of 12", ViewerFormat.pdfPage(index = 11, count = 12))
    }

    /**
     * One page not drawing says only that: the renderer gives no reason, and the file verified, so no
     * damage, no password and nothing about the rest of the file.
     */
    @Test
    fun a_page_that_would_not_draw_names_no_cause() {
        val text = ViewerFormat.pdfPageNotShown()
        assertTrue(text.contains("page", ignoreCase = true), "\"$text\" says it is this page")
        assertSaysNone(text, "damage", "corrupt", "broken", "password", "file", "PDF", "try", "later")
    }

    // ---- the page indicator -------------------------------------------------------------------------

    private val pdfMeta = GalleryItemMeta.Readable("report.pdf", "application/pdf")
    /** A space a line never breaks at (U+00A0), written as its code so no editor shows it as a space. */
    private val NBSP = Char(0x00A0)
    private val openPdf = ViewerPage.Pdf(pageCount = 12, pageSizes = List(12) { PdfPageSize(595, 842) })

    private val waited = ViewerFormat.PDF_DASHES_AFTER_MS

    private fun slot(page: ViewerPage, reading: Int = 0, loadingMs: Long = 0L, meta: GalleryItemMeta = pdfMeta) =
        ViewerFormat.pdfIndicator(page, meta, reading, loadingMs)

    /** The indicator in the slot, failing if the slot is empty or missing. */
    private fun shown(page: ViewerPage, reading: Int = 0, loadingMs: Long = waited): PageIndicator =
        assertIs<IndicatorSlot.Shown>(slot(page, reading = reading, loadingMs = loadingMs), "$page").indicator

    @Test
    fun an_open_pdf_names_the_page_being_read() {
        val indicator = shown(openPdf, reading = 2)
        assertEquals("Page 3 of 12", indicator.text.replace(NBSP, ' '))
        assertTrue(indicator.ready)
    }

    /** An open PDF names its page at once — even when its read was quick and never showed dashes. */
    @Test
    fun an_open_pdf_shows_its_page_whatever_the_wait() {
        assertEquals(shown(openPdf, reading = 2, loadingMs = waited), shown(openPdf, reading = 2, loadingMs = 0L))
    }

    /**
     * The first moments of a read keep the place empty: a small PDF opens, or a locked one is refused,
     * before dashes would have shown, so nothing flashes. Dashes come only once the read has taken
     * [ViewerFormat.PDF_DASHES_AFTER_MS].
     */
    @Test
    fun a_pdf_in_its_first_moments_of_reading_holds_an_empty_place() {
        assertEquals(IndicatorSlot.Held, slot(ViewerPage.Loading, loadingMs = 0L))
        assertEquals(IndicatorSlot.Held, slot(ViewerPage.Loading, loadingMs = waited - 1))
        assertFalse(shown(ViewerPage.Loading, loadingMs = waited).ready, "dashes once the read has taken a while")
    }

    /**
     * Every PDF that will not open keeps the indicator's place, empty — refused before any read (too large,
     * a device that shows no PDFs) or after it (locked, unreadable, damaged): to the reader they are all
     * the same, a message where pages would be, so Details and Delete sit where they do on every PDF.
     */
    @Test
    fun every_pdf_that_will_not_open_keeps_its_empty_place() {
        val refused = listOf(
            ViewerPage.TooLarge, ViewerPage.CantShow, ViewerPage.CouldntOpen,
            ViewerPage.PasswordProtected, ViewerPage.Unreadable, ViewerPage.Unverified,
        )
        for (page in refused) {
            assertEquals(IndicatorSlot.Held, slot(page), "$page")
            assertEquals(IndicatorSlot.Held, slot(page, loadingMs = waited), "$page after a long wait")
        }
    }

    /**
     * At a large font the indicator wraps, and only between "Page N" and "of M": its one breakable
     * space is the one before "of". The same for the dashes while loading.
     */
    @Test
    fun the_indicator_wraps_only_before_of() {
        val open = shown(openPdf, reading = 399).text
        assertEquals(listOf("Page${NBSP}400", "of${NBSP}12"), open.split(' '))
        val loading = shown(ViewerPage.Loading).text
        assertEquals(listOf("Page$NBSP–", "of$NBSP–"), loading.split(' '))
    }

    /** What a screen reader hears has plain spaces. */
    @Test
    fun the_spoken_indicator_has_no_unbreakable_spaces() {
        for (page in listOf(openPdf, ViewerPage.Loading)) {
            val spoken = shown(page, reading = 2).spoken
            assertFalse(spoken.contains(NBSP), "\"$spoken\"")
        }
    }

    /** A screen reader hears what the indicator does, not only what it says. */
    @Test
    fun an_open_pdf_indicator_is_spoken_as_the_way_to_jump() {
        val spoken = shown(openPdf, reading = 2).spoken
        assertTrue(spoken.startsWith("Page 3 of 12"), "\"$spoken\" names the page first")
        assertTrue(spoken.contains("jump to a page"), "\"$spoken\" says what a tap does")
    }

    /**
     * Still opening after a while: the dashes say the pages are coming, without numbers, in words a
     * screen reader can speak, and do not jump.
     */
    @Test
    fun a_pdf_still_opening_after_a_while_shows_dashes_without_numbers() {
        val indicator = shown(ViewerPage.Loading)
        assertFalse(indicator.text.any { it.isDigit() }, "\"${indicator.text}\" names no page")
        assertTrue(indicator.text.startsWith("Page"), "\"${indicator.text}\" has the same shape as the open one")
        assertFalse(indicator.spoken.any { it.isDigit() } || indicator.spoken.contains('–') || indicator.spoken.contains('-'))
        assertTrue(indicator.spoken.isNotBlank())
        assertFalse(indicator.ready)
    }

    @Test
    fun only_a_pdf_has_a_place_for_an_indicator() {
        val image = GalleryItemMeta.Readable("a.jpg", "image/jpeg")
        for (wait in listOf(0L, waited)) {
            assertNull(slot(ViewerPage.Loading, meta = image, loadingMs = wait), "an image loading, $wait ms")
            assertNull(slot(ViewerPage.Picture, meta = image, loadingMs = wait), "an image, $wait ms")
            assertNull(slot(ViewerPage.Unverified, meta = image, loadingMs = wait), "a damaged image, $wait ms")
        }
    }

    /** A PDF that will not open says why on its page: never dashes, which would promise pages to come. */
    @Test
    fun a_pdf_that_will_not_open_never_shows_an_indicator() {
        for (page in everyMessagePage) {
            for (wait in listOf(0L, waited)) {
                assertFalse(slot(page, loadingMs = wait) is IndicatorSlot.Shown, "$page, $wait ms")
            }
        }
    }

    // ---- fast scroll ---------------------------------------------------------------------------------

    /** The bubble is short — the bar already says "of M" — and counts from 1. */
    @Test
    fun the_fast_scroll_bubble_names_the_page_from_one() {
        assertEquals("Page 1", ViewerFormat.fastScrollPage(0))
        assertEquals("Page 400", ViewerFormat.fastScrollPage(399))
    }

    // ---- jump to a page ------------------------------------------------------------------------------

    @Test
    fun the_jump_dialog_says_what_it_does() {
        assertTrue(ViewerFormat.jumpTitle().contains("page", ignoreCase = true), ViewerFormat.jumpTitle())
        assertTrue(ViewerFormat.jumpGo().isNotBlank())
    }

    /** The field says which numbers it takes before anyone types a wrong one. */
    @Test
    fun the_jump_field_names_the_range() {
        val label = ViewerFormat.jumpFieldLabel(427)
        assertTrue(label.contains("1") && label.contains("427"), "\"$label\" names 1 and 427")
    }

    @Test
    fun a_jump_that_goes_says_nothing() {
        assertNull(ViewerFormat.jumpProblem(PageJump.Go(399), 427))
    }

    /**
     * A number no page has is told the range — and nothing suggesting the reader was taken anywhere,
     * because they were not.
     */
    @Test
    fun a_number_out_of_range_is_told_the_range() {
        val text = assertNotNull(ViewerFormat.jumpProblem(PageJump.OutOfRange, 427))
        assertTrue(text.contains("1") && text.contains("427"), "\"$text\" names the range")
        assertSaysNone(text, "nearest", "last page", "first page", "instead", "moved", "went")
    }

    /** Empty and not-a-number each say something, and not the same thing: one needs a number, one digits. */
    @Test
    fun empty_and_not_a_number_are_told_apart() {
        val empty = assertNotNull(ViewerFormat.jumpProblem(PageJump.Empty, 427))
        val notANumber = assertNotNull(ViewerFormat.jumpProblem(PageJump.NotANumber, 427))
        assertTrue(empty.isNotBlank() && notANumber.isNotBlank())
        assertTrue(empty != notANumber, "\"$empty\" and \"$notANumber\" differ")
        assertTrue(notANumber.contains("digit", ignoreCase = true), "\"$notANumber\" says what to type instead")
    }
}
