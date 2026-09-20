package dev.ehsanfahad.darkchamber.app.picker

import android.content.ContentResolver
import android.net.Uri
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
) : PickedFile {

    /**
     * Lazy, and queried once for both columns, because of *where* the eager version ran.
     *
     * The pick result is mapped on the coroutine that launched the picker, which is the main
     * dispatcher — so a fifty-file selection meant a hundred and fifty blocking binder round-trips
     * on the UI thread before the first byte was read, each of which a cloud provider can take its
     * time over. A single-file pick never showed it. Deferred like this, the queries happen on the
     * import dispatcher, as the batch reaches each file.
     */
    private val openable: OpenableColumnValues by lazy { resolver.queryOpenable(uri) }

    override val name: String by lazy { openable.displayName ?: uri.lastPathSegment ?: FALLBACK_NAME }

    override val mime: String by lazy { resolver.getType(uri) ?: FALLBACK_MIME }

    override val size: Long? by lazy { openableSizeOrNull(openable.size) }

    override fun openSource(): RawSource =
        resolver.openInputStream(uri)?.asSource()
            ?: error("the provider returned no stream for the picked file")

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

/** What one `OpenableColumns` lookup answered. Either field is null when the provider withheld it. */
private class OpenableColumnValues(val displayName: String?, val size: Long?)

/**
 * Both openable columns in one cursor. A null field means the provider omitted the column, left it
 * NULL, or could not be queried at all — each of which means the same thing to us, and none of which
 * is worth failing an import over.
 */
private fun ContentResolver.queryOpenable(uri: Uri): OpenableColumnValues {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    return query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use OpenableColumnValues(null, null)
        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
        OpenableColumnValues(
            displayName = if (nameColumn >= 0 && !cursor.isNull(nameColumn)) cursor.getString(nameColumn) else null,
            size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else null,
        )
    } ?: OpenableColumnValues(null, null)
}

/**
 * What a provider reported for `OpenableColumns.SIZE`, reduced to a size we will show or `null`.
 *
 * A negative value is how a provider says it does not know — a cloud file that has not been
 * downloaded is the common case — and that is not a number to draw a bar with. Zero is kept: an
 * empty file is a file.
 */
internal fun openableSizeOrNull(reported: Long?): Long? = reported?.takeIf { it >= 0L }
