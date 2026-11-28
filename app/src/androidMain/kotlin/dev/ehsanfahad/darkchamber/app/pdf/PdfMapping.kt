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
     * `SecurityException` is a document that needs a password; any other exception, or running out of
     * memory while parsing, is one it will not read; any other `Error` is no refusal (`null`) — the
     * process is in trouble, and it is rethrown once the descriptors are closed.
     * The words are never read — they differ from one Android version to the next.
     */
    fun refusalFor(failure: Throwable): PdfRefusal? =
        when (failure) {
            is SecurityException -> PdfRefusal.NeedsPassword

            is Exception -> PdfRefusal.Unreadable

            // Too big to parse in the memory left: this device will not read it.
            is OutOfMemoryError -> PdfRefusal.Unreadable

            else -> null
        }
}
