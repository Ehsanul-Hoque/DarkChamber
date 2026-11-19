package dev.ehsanfahad.darkchamber.feature.vault.api

/**
 * Why the platform will not show a PDF that read and verified. Told apart by what the platform reports
 * — its exception type or its document state — never by the words of a message, which change from one
 * platform version to the next.
 */
enum class PdfRefusal {
    /** The document is locked with a password. */
    NeedsPassword,

    /** The renderer will not read it: malformed, or using something it refuses. */
    Unreadable,

    /** This platform cannot show PDFs at all (Android before 11). */
    Unsupported,
}
