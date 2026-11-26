package dev.ehsanfahad.darkchamber.feature.vault.api

/**
 * What one page of the viewer shows. A description of the page, never its content: a decoded picture
 * is held apart from screen state (see [VaultViewerComponent.pictures]), and [Picture] only says that
 * one is there.
 */
sealed interface ViewerPage {
    /** Being read and decoded. Every path out of it ends in one of the others. */
    data object Loading : ViewerPage

    /** Decoded; the picture is in [VaultViewerComponent.pictures] under the item's key. */
    data object Picture : ViewerPage

    /**
     * A video or audio item. While it is the page shown its player is in [VaultViewerComponent.players]
     * under the item's key; a page beside it has no player. [note] says which part of it this device
     * cannot play, while the rest plays.
     */
    data class Media(
        val kind: PlayerKind,
        val note: TrackNote? = null,
    ) : ViewerPage

    /**
     * A PDF that read, verified and opened: [pageCount] pages, each of the size in [pageSizes]. Its
     * drawn pages are in [VaultViewerComponent.pdfPages] while it is the page shown; [start] is where
     * reading resumes — the top, or where it was left earlier in this viewer.
     */
    data class Pdf(
        val pageCount: Int,
        val pageSizes: List<PdfPageSize>,
        val start: PdfPlace = PdfPlace(),
    ) : ViewerPage

    /**
     * A text file that read and verified and is shown as text, of [kind]. The text itself is in
     * [VaultViewerComponent.texts] under the item's key while it is the page shown. [note] says why a
     * Markdown or JSON file is shown only as written — `null` when its formatted view is there too (and
     * always for the kinds that have none).
     */
    data class Text(
        val kind: TextKind,
        val note: FormatNote? = null,
    ) : ViewerPage

    /**
     * Read and verified, but not text this viewer reads: bytes that are not valid UTF-8 or UTF-16 with
     * a byte-order mark, or that hold a NUL. Not damage: the vault checked it is the file that was added.
     */
    data object NotText : ViewerPage

    /** A kind with no viewer here — anything but an image, video, audio, PDF or text — shown as its [badge]. Nothing was read. */
    data class NotViewable(
        val badge: String,
    ) : ViewerPage

    /** Larger than the viewer reads into memory. Nothing was read. */
    data object TooLarge : ViewerPage

    /** Read and verified, but the platform could not make a picture of it. Not damage. */
    data object CantShow : ViewerPage

    /**
     * A video or audio item none of whose tracks this device's decoders play. Not damage: what was read
     * verified, and the file is unchanged in the vault.
     */
    data object CantPlay : ViewerPage

    /**
     * A PDF that read and verified but is locked with a password, which cannot be entered here. Not
     * damage: it is the file that was added.
     */
    data object PasswordProtected : ViewerPage

    /**
     * A PDF that read and verified but that this device's renderer will not read — malformed, or using
     * something the renderer refuses. Not damage: the vault checked it is the file that was added.
     */
    data object Unreadable : ViewerPage

    /** Failed its integrity check, or is not a container at all. Nothing of it is shown. */
    data object Unverified : ViewerPage

    /**
     * A video or audio item the vault refused partway through playing: what played before was each
     * verified chunk, and playback stopped where a chunk failed. Not [Unverified], whose words say
     * none of the file was shown.
     */
    data object StoppedUnverified : ViewerPage

    /** Written by a newer version of the format; the app needs updating to open it. */
    data object NeedsUpdate : ViewerPage

    /** Would not open, for a reason that names no cause. */
    data object CouldntOpen : ViewerPage
}

/** How a text item is shown: the kind decides the font, and which items have a formatted view. */
enum class TextKind {
    /** Prose: the reading font. */
    Plain,

    /** Rendered, with a plain view beside it. */
    Markdown,

    /** Re-indented, with a plain view beside it; monospace. */
    Json,

    /** Markup, data, logs and source: monospace, shown as written. */
    Code,
}

/** Why a Markdown or JSON file is shown only as written. */
enum class FormatNote {
    /** Larger than this viewer formats. */
    TooLarge,

    /** Nested too deeply, or too slow, to format here. */
    TooComplex,

    /** A JSON file that is not valid JSON. */
    NotJson,
}

/** What a player page plays: a picture with its sound, or sound alone. */
enum class PlayerKind { Video, Audio }

/** What a playing page says is missing: its picture, or its sound. */
enum class TrackNote { NoPicture, NoSound }
