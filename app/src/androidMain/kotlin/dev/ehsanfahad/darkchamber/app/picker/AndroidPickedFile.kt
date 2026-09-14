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

    override val name: String = resolver.queryDisplayName(uri) ?: uri.lastPathSegment ?: FALLBACK_NAME

    override val mime: String = resolver.getType(uri) ?: FALLBACK_MIME

    override val size: Long? = openableSizeOrNull(resolver.querySize(uri))

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

private fun ContentResolver.queryDisplayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }

// Null when the provider omits the column, leaves it NULL, or cannot be queried at all — each of
// which means the same thing to us, and none of which is worth failing an import over.
private fun ContentResolver.querySize(uri: Uri): Long? =
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
    }

/**
 * What a provider reported for `OpenableColumns.SIZE`, reduced to a size we will show or `null`.
 *
 * A negative value is how a provider says it does not know — a cloud file that has not been
 * downloaded is the common case — and that is not a number to draw a bar with. Zero is kept: an
 * empty file is a file.
 */
internal fun openableSizeOrNull(reported: Long?): Long? = reported?.takeIf { it >= 0L }
