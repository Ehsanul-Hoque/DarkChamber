package dev.ehsanfahad.darkchamber.app.picker

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.io.RawSource
import kotlinx.io.asSource

/**
 * A file the user picked, read through the content resolver. Each [openSource] opens a fresh stream,
 * which is what the `.dchm` codec's two passes need; a provider that refuses the second open is
 * handled upstream by the import's spill path.
 *
 * Shared by both pickers: a document and a photo arrive as the same kind of `content://` URI, and
 * the resolver answers the same questions about either.
 */
internal class AndroidPickedFile(
    private val resolver: ContentResolver,
    private val uri: Uri,
    /**
     * Whether this URI came from the **document** picker rather than the photo picker.
     *
     * It is settled at the call site because only the picker knows: the two look alike here, and
     * telling them apart afterwards would need a `Context` this class deliberately does not hold.
     * It decides two things — whether the flags column is worth asking for at all, and whether
     * deletion can ever be offered.
     */
    private val document: Boolean,
) : PickedFile {

    /**
     * Lazy, and queried once for every column, because of *where* the eager version ran.
     *
     * The pick result is mapped on the coroutine that launched the picker, which is the main
     * dispatcher — so a fifty-file selection meant a hundred and fifty blocking binder round-trips
     * on the UI thread before the first byte was read, each of which a cloud provider can take its
     * time over. A single-file pick never showed it. Deferred like this, the queries happen on the
     * import dispatcher, as the batch reaches each file.
     *
     * The delete flag rides along in the same projection for the same reason: knowing whether an
     * original can be deleted is worth no extra round-trip of its own.
     */
    private val openable: OpenableColumnValues by lazy { resolver.queryOpenable(uri, withFlags = document) }

    override val name: String by lazy { openable.displayName ?: uri.lastPathSegment ?: FALLBACK_NAME }

    override val mime: String by lazy { resolver.getType(uri) ?: FALLBACK_MIME }

    override val size: Long? by lazy { openableSizeOrNull(openable.size) }

    override val deletable: Boolean by lazy { supportsDelete(document, openable.flags) }

    override fun openSource(): RawSource =
        resolver.openInputStream(uri)?.asSource()
            ?: error("the provider returned no stream for the picked file")

    /**
     * Asks the provider that owns the file to delete it, which it may still refuse.
     *
     * The flag is only an advertisement: a provider can go on to fail the call, and `deleteDocument`
     * throws for a document that has moved or a grant that no longer covers it. None of that is an
     * error the caller should act on — the bytes are already in the vault by now — so every failure
     * reads the same as a refusal.
     *
     * Works on a closed file, as the seam promises, because the read grant belongs to the activity
     * that ran the picker rather than to any stream we opened; [close] here releases nothing.
     */
    override fun deleteOriginal(): Boolean {
        if (!deletable) return false
        return try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Nothing to release: the read grant is not persistable, so it expires with the activity that
     * requested it. Kept for the contract — the iOS side has a real resource to hand back.
     */
    override fun close() = Unit

    private companion object {
        const val FALLBACK_NAME = "file"
        const val FALLBACK_MIME = "application/octet-stream"
    }
}

/** What one column lookup answered. A null field means the provider withheld that one. */
private class OpenableColumnValues(val displayName: String?, val size: Long?, val flags: Int?)

/** Nothing known, which is a state every failure path here collapses into. */
private val NOTHING_REPORTED = OpenableColumnValues(null, null, null)

/**
 * Every column we want in one cursor. A null field means the provider omitted the column, left it
 * NULL, or could not be queried at all — each of which means the same thing to us, and none of which
 * is worth failing an import over.
 *
 * The flags column is only asked for when the URI is a document, since it is part of the documents
 * contract and nothing else promises to answer it. A provider that dislikes a column in a projection
 * is entitled to throw rather than ignore it, and losing the display name over a capability we would
 * merely have *offered* would be a poor trade — hence the catch.
 */
private fun ContentResolver.queryOpenable(uri: Uri, withFlags: Boolean): OpenableColumnValues {
    val projection = buildList {
        add(OpenableColumns.DISPLAY_NAME)
        add(OpenableColumns.SIZE)
        if (withFlags) add(Document.COLUMN_FLAGS)
    }.toTypedArray()
    val cursor = try {
        query(uri, projection, null, null, null)
    } catch (_: Exception) {
        null
    }
    return cursor?.use {
        if (!it.moveToFirst()) return@use NOTHING_REPORTED
        val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
        val flagsColumn = it.getColumnIndex(Document.COLUMN_FLAGS)
        OpenableColumnValues(
            displayName = if (nameColumn >= 0 && !it.isNull(nameColumn)) it.getString(nameColumn) else null,
            size = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn) else null,
            flags = if (flagsColumn >= 0 && !it.isNull(flagsColumn)) it.getInt(flagsColumn) else null,
        )
    } ?: NOTHING_REPORTED
}

/**
 * Whether an offer to delete this original is worth making.
 *
 * Both halves fail independently and both are ordinary. A photo-picker item is not a document, so
 * there is nothing `DocumentsContract` could delete — a platform fact, not something left
 * unimplemented here. And a document only supports deletion if it says so: a document opened
 * read-only never will, while a cloud provider may well — Google Drive advertises it, observed on a
 * device — so this is not a local-files-only capability. The flag stays an advertisement either way:
 * `deleteOriginal` reports what happened rather than assuming the call it was offered succeeds.
 */
internal fun supportsDelete(isDocument: Boolean, flags: Int?): Boolean =
    isDocument && flags != null && (flags and Document.FLAG_SUPPORTS_DELETE) != 0

/**
 * What a provider reported for `OpenableColumns.SIZE`, reduced to a size we will show or `null`.
 *
 * A negative value is how a provider says it does not know — a cloud file that has not been
 * downloaded is the common case — and that is not a number to draw a bar with. Zero is kept: an
 * empty file is a file.
 */
internal fun openableSizeOrNull(reported: Long?): Long? = reported?.takeIf { it >= 0L }
