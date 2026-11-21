package dev.ehsanfahad.darkchamber.app.pdf

import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
