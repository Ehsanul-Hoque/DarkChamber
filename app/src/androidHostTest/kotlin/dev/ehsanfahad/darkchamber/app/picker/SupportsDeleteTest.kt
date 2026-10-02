package dev.ehsanfahad.darkchamber.app.picker

import android.provider.DocumentsContract.Document
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether we are willing to offer to delete a picked original.
 *
 * Two things have to hold, and they fail independently. The URI must be a **document** — the photo
 * picker hands back media URIs that no provider will delete, so asking is meaningless there — and
 * the document must advertise `FLAG_SUPPORTS_DELETE`, which a read-only document never does and a
 * cloud provider may well (Google Drive advertises it, observed on a device). Either missing means
 * no offer at all, which is the ordinary case rather than an error (hard rule #6).
 *
 * The decision is pulled out here because the rest of the delete path needs a live provider and can
 * only be verified on a device.
 */
class SupportsDeleteTest {

    @Test
    fun a_document_that_advertises_deletion_can_be_offered() {
        assertTrue(supportsDelete(isDocument = true, flags = Document.FLAG_SUPPORTS_DELETE))
    }

    @Test
    fun deletion_is_read_out_of_a_flag_set_that_carries_other_capabilities_too() {
        val flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE
        assertTrue(supportsDelete(isDocument = true, flags = flags))
    }

    @Test
    fun a_writable_document_that_says_nothing_about_deletion_is_not_offered() {
        // The case that separates reading the one flag from reading "has any capability at all".
        assertFalse(supportsDelete(isDocument = true, flags = Document.FLAG_SUPPORTS_WRITE))
    }

    @Test
    fun a_document_with_no_capabilities_is_not_offered() {
        assertFalse(supportsDelete(isDocument = true, flags = 0))
    }

    @Test
    fun a_provider_that_withheld_the_column_is_not_offered() {
        assertFalse(supportsDelete(isDocument = true, flags = null))
    }

    @Test
    fun a_photo_picker_item_is_never_offered_however_it_is_flagged() {
        // Not a document URI, so there is nothing to hand DocumentsContract regardless of flags.
        assertFalse(supportsDelete(isDocument = false, flags = Document.FLAG_SUPPORTS_DELETE))
    }
}
