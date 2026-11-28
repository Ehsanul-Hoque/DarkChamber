package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a viewer page shows, decided before and after the read — one test per kind of outcome, so a
 * branch swapped in the screen would have nowhere to hide.
 */
class ViewerPageTest {
    private val cap = 64L * 1024 * 1024

    private fun readable(mime: String) = GalleryItemMeta.Readable(name = "x", mime = mime)

    @Test
    fun an_image_within_the_cap_is_decoded() {
        assertEquals(OpenPlan.Decode, openPlan(readable("image/jpeg"), size = 1_000, cap = cap))
        assertEquals(
            OpenPlan.Decode,
            openPlan(readable("image/heic"), size = cap, cap = cap),
            "at the cap is within it",
        )
    }

    /** Other kinds get their own viewers later; until then the page says so, and reads nothing. */
    @Test
    fun a_kind_with_no_viewer_yet_is_shown_as_its_badge_without_a_read() {
        assertEquals(OpenPlan.Show(ViewerPage.NotViewable("VID")), openPlan(readable("video/mp4"), 1_000, cap))
        assertEquals(OpenPlan.Show(ViewerPage.NotViewable("AUD")), openPlan(readable("audio/mpeg"), 1_000, cap))
        assertEquals(OpenPlan.Show(ViewerPage.NotViewable("PDF")), openPlan(readable("application/pdf"), 1_000, cap))
        assertEquals(OpenPlan.Show(ViewerPage.NotViewable("FILE")), openPlan(readable("application/zip"), 1_000, cap))
        val markdown = GalleryItemMeta.Readable(name = "notes.md", mime = "application/octet-stream")
        assertEquals(OpenPlan.Show(ViewerPage.NotViewable("TXT")), openPlan(markdown, 1_000, cap), "the grid's badge")
    }

    /** Over the cap, nothing is read into memory at all — the size column is plaintext. */
    @Test
    fun an_image_over_the_cap_is_not_read() {
        assertEquals(OpenPlan.Show(ViewerPage.TooLarge), openPlan(readable("image/jpeg"), size = cap + 1, cap = cap))
    }

    /**
     * An item whose name will not open has no known type — but its object has its own key in the
     * row, so it may well open. The decoder decides from the bytes; the cap still applies.
     */
    @Test
    fun an_item_whose_details_will_not_open_is_still_tried_within_the_cap() {
        assertEquals(OpenPlan.Decode, openPlan(GalleryItemMeta.Unreadable, size = 1_000, cap = cap))
        assertEquals(
            OpenPlan.Show(ViewerPage.TooLarge),
            openPlan(GalleryItemMeta.Unreadable, size = cap + 1, cap = cap),
        )
    }

    // ---- a PDF, before it is read ----------------------------------------------------------------

    @Test
    fun a_pdf_is_known_by_its_stored_type_alone() {
        assertTrue(isPdf(readable("application/pdf")))
        assertFalse(isPdf(readable("image/jpeg")), "an image is not")
        assertFalse(isPdf(readable("application/zip")), "nor another document")
        assertFalse(isPdf(GalleryItemMeta.Unreadable), "an item of unknown type stays on the image route")
    }

    @Test
    fun a_pdf_within_the_cap_is_read() {
        assertEquals(OpenPlan.Decode, pdfPlan(size = 1_000, supported = true))
        assertEquals(OpenPlan.Decode, pdfPlan(size = PDF_CAP, supported = true), "at the cap is within it")
    }

    /** The whole plaintext is held in memory while shown, so the cap is checked from the size column first. */
    @Test
    fun a_pdf_over_the_cap_is_not_read() {
        assertEquals(OpenPlan.Show(ViewerPage.TooLarge), pdfPlan(size = PDF_CAP + 1, supported = true))
    }

    @Test
    fun the_pdf_cap_is_128_mib() {
        assertEquals(128L * 1024 * 1024, PDF_CAP)
    }

    /** A platform that cannot show PDFs is told before anything is decrypted, whatever the size. */
    @Test
    fun a_pdf_on_a_platform_that_cannot_show_one_is_not_read() {
        assertEquals(OpenPlan.Show(ViewerPage.CantShow), pdfPlan(size = 1_000, supported = false))
        assertEquals(OpenPlan.Show(ViewerPage.CantShow), pdfPlan(size = PDF_CAP + 1, supported = false))
    }

    @Test
    fun a_pdf_with_a_size_nothing_here_wrote_is_not_read_and_names_no_cause() {
        assertEquals(OpenPlan.Show(ViewerPage.CouldntOpen), pdfPlan(size = -1, supported = true))
    }

    // ---- a PDF that read and verified, refused by the platform -----------------------------------

    @Test
    fun a_pdf_locked_with_a_password_has_its_own_page() {
        assertEquals(ViewerPage.PasswordProtected, pageForPdfRefusal(PdfRefusal.NeedsPassword))
    }

    @Test
    fun a_pdf_the_renderer_will_not_read_is_unreadable_never_unverified() {
        assertEquals(ViewerPage.Unreadable, pageForPdfRefusal(PdfRefusal.Unreadable))
    }

    @Test
    fun a_platform_that_cannot_show_pdfs_puts_the_limit_on_the_device() {
        assertEquals(ViewerPage.CantShow, pageForPdfRefusal(PdfRefusal.Unsupported))
    }

    /** The vault verified every one of these: none may become a page that suggests damage. */
    @Test
    fun no_refusal_of_a_verified_pdf_is_ever_an_unverified_page() {
        for (refusal in PdfRefusal.entries) {
            val page = pageForPdfRefusal(refusal)
            assertFalse(page == ViewerPage.Unverified || page == ViewerPage.StoppedUnverified, "$refusal → $page")
        }
    }

    @Test
    fun a_failed_integrity_check_is_an_unverified_page() {
        assertEquals(ViewerPage.Unverified, pageForOpenFailure(VaultError.Crypto.IntegrityFailure))
        assertEquals(ViewerPage.Unverified, pageForOpenFailure(VaultError.Crypto.MalformedContainer))
    }

    /** A newer format is a reason to update, never damage (the "old vaults always open" rule's mirror). */
    @Test
    fun a_file_from_a_newer_app_asks_for_an_update() {
        assertEquals(ViewerPage.NeedsUpdate, pageForOpenFailure(VaultError.Crypto.UnsupportedFormatVersion))
    }

    /** Anything else names no cause — and in particular is never read as an integrity failure. */
    @Test
    fun any_other_failure_is_couldnt_open_and_never_unverified() {
        for (error in listOf(
            VaultError.Io(RuntimeException()),
            VaultError.Db(RuntimeException()),
            VaultError.Unexpected(RuntimeException()),
            VaultError.Storage.Full,
            VaultError.Storage.DestinationMissing,
            VaultError.Auth.Failed,
        )) {
            assertEquals(ViewerPage.CouldntOpen, pageForOpenFailure(error), "for $error")
        }
    }
}
