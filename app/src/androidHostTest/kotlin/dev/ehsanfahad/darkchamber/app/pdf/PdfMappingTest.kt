package dev.ehsanfahad.darkchamber.app.pdf

import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `PdfRenderer`'s refusals mean — by exception type, as its documentation names them, never by
 * message (the messages were seen to differ between Android 12, 13 and 17).
 */
class PdfMappingTest {
    @Test
    fun pdfs_are_shown_from_android_11() {
        assertFalse(PdfMapping.supported(29), "Android 10 has no memory-only file in its Java API")
        assertTrue(PdfMapping.supported(30))
        assertTrue(PdfMapping.supported(37))
    }

    @Test
    fun the_first_sdk_is_android_11() {
        assertEquals(30, PdfMapping.FIRST_SDK)
    }

    /** `PdfRenderer` throws `SecurityException` for a document that needs a password. */
    @Test
    fun a_security_exception_means_a_password() {
        assertEquals(PdfRefusal.NeedsPassword, PdfMapping.refusalFor(SecurityException("password required")))
    }

    /** `IOException` for one it cannot read; anything else is no better known. */
    /**
     * A file too big for the renderer to parse in the memory left is one this device will not read:
     * declined like any other, not a crash — and its descriptor closed, which a crash would leave to
     * the finaliser.
     */
    @Test
    fun running_out_of_memory_while_opening_means_unreadable() {
        assertEquals(PdfRefusal.Unreadable, PdfMapping.refusalFor(OutOfMemoryError()))
    }

    /** Any other `Error` is not a refusal of this file but something wrong with the process: let it go. */
    @Test
    fun any_other_error_is_not_a_refusal() {
        assertNull(PdfMapping.refusalFor(StackOverflowError()))
        assertNull(PdfMapping.refusalFor(AssertionError("x")))
    }

    @Test
    fun anything_else_means_unreadable_whatever_it_says() {
        assertEquals(PdfRefusal.Unreadable, PdfMapping.refusalFor(IOException("file not in PDF format or corrupted")))
        assertEquals(PdfRefusal.Unreadable, PdfMapping.refusalFor(IllegalArgumentException("x")))
        assertEquals(
            PdfRefusal.Unreadable,
            PdfMapping.refusalFor(IOException("password required")),
            "the words are never read: only the type",
        )
    }
}
