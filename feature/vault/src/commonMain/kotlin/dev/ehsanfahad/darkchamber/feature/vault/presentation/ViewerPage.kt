package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerFailure
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerTracks
import dev.ehsanfahad.darkchamber.feature.vault.api.TextKind
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackNote
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage

/** What to do with an item before anything is read. */
internal sealed interface OpenPlan {
    data object Decode : OpenPlan

    data class Show(val page: ViewerPage) : OpenPlan
}

/**
 * Before anything is read: decode it, or show a page that needs no read at all.
 *
 * - A kind with no viewer yet (video, audio, PDF, anything else) shows its badge, and reads nothing.
 * - Anything larger than [cap] is not read into memory: [size] is a plaintext column, so this costs
 *   no decrypt, and an image too big to hold must not take the heap a passphrase unlock also needs.
 * - An item whose details will not open has no known type, but its object has its own key in the
 *   row and may open fine — so it is tried, within the cap, and the decoder decides from the bytes.
 * - A negative size is a row nothing here wrote: nothing is read, and the page names no cause.
 */
internal fun openPlan(meta: GalleryItemMeta, size: Long, cap: Long): OpenPlan {
    if (meta is GalleryItemMeta.Readable && !meta.mime.startsWith("image/")) {
        return OpenPlan.Show(ViewerPage.NotViewable(GalleryFormat.typeBadge(meta)))
    }
    return when {
        size < 0 -> OpenPlan.Show(ViewerPage.CouldntOpen)
        size > cap -> OpenPlan.Show(ViewerPage.TooLarge)
        else -> OpenPlan.Decode
    }
}

/** The largest PDF the viewer reads: its whole plaintext is held in memory while it is shown. */
internal const val PDF_CAP: Long = 128L * 1024 * 1024

/** Whether an item is a PDF, from its stored type alone, before anything is read. */
internal fun isPdf(meta: GalleryItemMeta): Boolean =
    meta is GalleryItemMeta.Readable && meta.mime == PDF_MIME

private const val PDF_MIME = "application/pdf"

/**
 * Before a PDF is read: read it, or show a page that needs no read at all.
 *
 * - A platform that cannot show PDFs says so before anything is decrypted, whatever the size: the
 *   device is the limit, not the file.
 * - Anything larger than [cap] is not read: its whole plaintext would sit in memory while shown, and
 *   the size is a plaintext column, so refusing costs no decrypt.
 * - A negative size is a row nothing here wrote: nothing is read, and the page names no cause.
 */
internal fun pdfPlan(size: Long, supported: Boolean, cap: Long = PDF_CAP): OpenPlan =
    when {
        !supported -> OpenPlan.Show(ViewerPage.CantShow)
        size < 0 -> OpenPlan.Show(ViewerPage.CouldntOpen)
        size > cap -> OpenPlan.Show(ViewerPage.TooLarge)
        else -> OpenPlan.Decode
    }

/**
 * The page for a PDF that read and verified but that the platform will not show. The vault checked
 * every byte, so none of these is damage: a password is the document's own lock, a renderer's refusal
 * is the renderer's limit, and a platform without PDFs is the device's.
 */
internal fun pageForPdfRefusal(refusal: PdfRefusal): ViewerPage =
    when (refusal) {
        PdfRefusal.NeedsPassword -> ViewerPage.PasswordProtected
        PdfRefusal.Unreadable -> ViewerPage.Unreadable
        PdfRefusal.Unsupported -> ViewerPage.CantShow
    }

/**
 * Whether the viewer reads an item as text, and of which kind — from its stored details alone,
 * before anything is read. `null` when it is not text.
 *
 * The stored type depends on where the file came from (a provider may record a Markdown file as
 * `text/markdown`, `text/plain` or `application/octet-stream`), so a known extension in the stored
 * name counts too, and when both are there the extension names the kind: a `.json` saved as plain
 * text is JSON. A kind with its own viewer — a picture, a video, a sound, a PDF — keeps it whatever
 * the name says. This only decides what to try: the bytes still decide whether it is text at all.
 */
internal fun textKind(meta: GalleryItemMeta): TextKind? {
    if (meta !is GalleryItemMeta.Readable) return null
    val type = meta.mime.substringBefore(';').trim().lowercase()
    if (OWN_VIEWER_FAMILIES.any { type.startsWith(it) } || type == PDF_MIME) return null
    return kindByExtension(meta.name) ?: kindByType(type)
}

private val OWN_VIEWER_FAMILIES = listOf("image/", "video/", "audio/")

private fun kindByExtension(name: String): TextKind? {
    val dot = name.lastIndexOf('.')
    if (dot < 0) return null
    return TEXT_EXTENSIONS[name.substring(dot + 1).lowercase()]
}

private fun kindByType(type: String): TextKind? =
    when {
        type == "text/plain" -> TextKind.Plain
        type == "text/markdown" || type == "text/x-markdown" -> TextKind.Markdown
        type == "application/json" || type.endsWith("+json") -> TextKind.Json
        type.startsWith("text/") -> TextKind.Code
        type in CODE_TYPES || type.endsWith("+xml") -> TextKind.Code
        else -> null
    }

private val CODE_TYPES = setOf(
    "application/xml",
    "application/yaml",
    "application/x-yaml",
    "application/toml",
    "application/javascript",
    "application/x-sh",
    "application/sql",
)

private val TEXT_EXTENSIONS: Map<String, TextKind> =
    listOf("txt", "text").associateWith { TextKind.Plain } +
        listOf("md", "markdown", "mdown", "mkd").associateWith { TextKind.Markdown } +
        listOf("json").associateWith { TextKind.Json } +
        listOf(
            // Markup and data.
            "html", "htm", "xhtml", "xml", "csv", "tsv", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "properties", "log", "srt", "vtt",
            // Source.
            "kt", "kts", "java", "swift", "py", "js", "ts", "c", "h", "cpp", "hpp", "cs", "go", "rs",
            "rb", "php", "sh", "sql", "css", "gradle",
        ).associateWith { TextKind.Code }

/** The largest text file the viewer reads: its bytes, then its decoded text, are held while shown. */
internal const val TEXT_CAP: Long = 8L * 1024 * 1024

/** The largest Markdown file the viewer formats; a larger one is shown as written, with a note. */
internal const val MARKDOWN_FORMAT_CAP: Long = 1L * 1024 * 1024

/**
 * Before a text item is read: read it, or show a page that needs no read at all.
 *
 * - Anything larger than [cap] is not read: the size is a plaintext column, so refusing costs no
 *   decrypt. An empty file is read — it is text with nothing in it.
 * - A negative size is a row nothing here wrote: nothing is read, and the page names no cause.
 */
internal fun textPlan(size: Long, cap: Long = TEXT_CAP): OpenPlan =
    when {
        size < 0 -> OpenPlan.Show(ViewerPage.CouldntOpen)
        size > cap -> OpenPlan.Show(ViewerPage.TooLarge)
        else -> OpenPlan.Decode
    }

/**
 * Which player an item gets: a stored video or audio type plays; anything else, and an item whose
 * details will not open — its type unknown — does not (`null`). Decided from the stored type alone,
 * before anything is read.
 */
internal fun mediaKind(meta: GalleryItemMeta): PlayerKind? {
    if (meta !is GalleryItemMeta.Readable) return null
    return when {
        meta.mime.startsWith("video/") -> PlayerKind.Video
        meta.mime.startsWith("audio/") -> PlayerKind.Audio
        else -> null
    }
}

/**
 * The page for an object that would not open. Only a failed integrity check (or a container that is
 * not one) is [ViewerPage.Unverified]; a newer format asks for an update; anything else names no
 * cause. A lock is none of these — the session refuses by throwing, and the screen is gone.
 */
internal fun pageForOpenFailure(error: VaultError): ViewerPage =
    when (error) {
        VaultError.Crypto.IntegrityFailure, VaultError.Crypto.MalformedContainer -> ViewerPage.Unverified
        VaultError.Crypto.UnsupportedFormatVersion -> ViewerPage.NeedsUpdate
        else -> ViewerPage.CouldntOpen
    }

/**
 * The page for playback that ended early, decided by where the failure came from: a refused read is
 * judged as an open would be ([pageForOpenFailure]), so only the vault's own integrity check makes a
 * file unverified (mid-play, [ViewerPage.StoppedUnverified]); a decoder that declines the format is
 * [ViewerPage.CantPlay], never damage. A lock is no failure at all: it stops the viewer listening
 * before the session closes, so nothing the released player reports afterwards reaches here.
 */
internal fun pageForPlayerFailure(failure: PlayerFailure): ViewerPage =
    when (failure) {
        // Mid-play, the verified chunks before the refused one have already played.
        is PlayerFailure.Reader -> pageForOpenFailure(failure.error).let {
            if (it == ViewerPage.Unverified) ViewerPage.StoppedUnverified else it
        }
        PlayerFailure.Decoder -> ViewerPage.CantPlay
        PlayerFailure.Other -> ViewerPage.CouldntOpen
    }

/**
 * The note for an item that plays in part: one kind of track this device cannot play while the other
 * plays. `null` when everything found plays, when there is nothing to play at all (that is
 * [nothingPlays]'s page), and before the tracks are known. A track the item simply does not have — a
 * silent clip, an audio file's picture — is not missing.
 */
internal fun trackNote(tracks: PlayerTracks): TrackNote? =
    when {
        tracks.video == TrackSupport.Unplayable && tracks.audio == TrackSupport.Playable -> TrackNote.NoPicture
        tracks.audio == TrackSupport.Unplayable && tracks.video == TrackSupport.Playable -> TrackNote.NoSound
        else -> null
    }

/** Every track found is one this device cannot play: the whole page is [ViewerPage.CantPlay]. */
internal fun nothingPlays(tracks: PlayerTracks): Boolean {
    val found = listOf(tracks.video, tracks.audio).filter { it != TrackSupport.Absent }
    return found.isNotEmpty() && found.none { it == TrackSupport.Playable }
}
