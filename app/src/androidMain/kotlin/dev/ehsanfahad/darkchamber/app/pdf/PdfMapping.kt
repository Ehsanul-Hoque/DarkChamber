package dev.ehsanfahad.darkchamber.app.pdf

import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal

/** What the Android PDF renderer's answers mean, decided where a host test can reach it. */
internal object PdfMapping {
    /** The first Android whose Java API makes a memory-only file (`Os.memfd_create`): Android 11. */
    const val FIRST_SDK = 30

    /**
     * Whether PDFs can be shown at [sdkInt]. Before Android 11 the only way to hand `PdfRenderer` the
     * plaintext would be a file on storage, which this app does not write — so those versions say so.
     */
    fun supported(sdkInt: Int): Boolean = sdkInt >= FIRST_SDK

    /**
     * Why `PdfRenderer` refused a verified file, by its exception's **type** as documented: a
     * `SecurityException` is a document that needs a password; anything else is one it will not read.
     * The words are never read — they differ from one Android version to the next.
     */
    fun refusalFor(failure: Exception): PdfRefusal =
        if (failure is SecurityException) PdfRefusal.NeedsPassword else PdfRefusal.Unreadable
}
