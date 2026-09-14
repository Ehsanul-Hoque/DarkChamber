package dev.ehsanfahad.darkchamber.app.picker

import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL

/**
 * A picked file read straight from where it already lives — never copied into our container.
 * [close] gives back the security-scoped resource, so a picked file must always be closed,
 * including when the import fails.
 *
 * Shared by both iOS pickers: a document and a photo both arrive as a security-scoped URL.
 *
 * [scratch] is the exception, and only the photo picker ever sets it. When the system hands us a
 * *copy* rather than the original — an iCloud asset it had to materialise — that copy is ours to
 * delete, and [close] deletes it. Leaving plaintext from someone's library lying in our container
 * after the import is exactly what hard rule #1 is about.
 */
internal class IosPickedFile(
    private val url: NSURL,
    private val path: String,
    private val scoped: Boolean,
    private val scratch: NSURL? = null,
) : PickedFile {

    override val name: String = url.lastPathComponent ?: FALLBACK_NAME

    // The picker deals in files, not MIME; the vault stores a type it can map to a viewer later.
    override val mime: String = mimeForExtension(url.pathExtension)

    override val size: Long? = fileSizeOrNull(path)

    override fun openSource(): RawSource = SystemFileSystem.source(Path(path))

    @OptIn(ExperimentalForeignApi::class)
    override fun close() {
        if (scoped) url.stopAccessingSecurityScopedResource()
        scratch?.let { NSFileManager.defaultManager.removeItemAtURL(it, error = null) }
    }

    private companion object {
        const val FALLBACK_NAME = "file"
    }
}

/**
 * The file's size from the filesystem, or `null` if it cannot be read.
 *
 * An iCloud file that has not been downloaded still reports its size, so this usually succeeds —
 * but the attribute lookup can fail (the file moved, the scoped access was refused), and an unknown
 * size is a display concern only. Nothing about the import depends on it, so a failure here must
 * never become a failure to import.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun fileSizeOrNull(path: String): Long? =
    (NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)
        ?.longLongValue
        ?.takeIf { it >= 0L }

/** A deliberately small map: enough for the viewers v1 ships, honest `application/octet-stream` otherwise. */
internal fun mimeForExtension(extension: String?): String =
    when (extension?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        else -> "application/octet-stream"
    }
