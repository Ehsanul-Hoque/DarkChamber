package dev.ehsanfahad.darkchamber.feature.vault.presentation

/**
 * Pure formatting for a gallery tile. Kept out of the composable so it can be tested directly, and
 * so the rules — what a size reads as, how coarse an age is — live in one place.
 */
internal object GalleryFormat {

    /**
     * A short type badge for the media slot, standing in for the thumbnail until thumbnails exist.
     * Derived from the MIME family, so an unknown type still gets something honest rather than a
     * guess at the content.
     */
    fun typeBadge(mime: String): String =
        when {
            mime.startsWith("image/") -> "IMG"
            mime.startsWith("video/") -> "VID"
            mime.startsWith("audio/") -> "AUD"
            mime == "application/pdf" -> "PDF"
            mime.startsWith("text/") -> "TXT"
            else -> "FILE"
        }

    /** Binary units, one decimal above a kibibyte — the sizes a file manager would show. */
    fun size(bytes: Long): String {
        if (bytes < KIB) return "$bytes B"
        var value = bytes.toDouble() / KIB
        for (unit in UNITS) {
            if (value < KIB) return "${oneDecimal(value)} $unit"
            value /= KIB
        }
        return "${oneDecimal(value * KIB)} ${UNITS.last()}"
    }

    /**
     * A coarse age. Deliberately imprecise: the exact second a file entered the vault is not
     * information a listing needs to broadcast, and coarse labels stay readable as a vault ages.
     * A [createdAt] in the future (a clock that moved backwards) reads as "just now" rather than a
     * negative age.
     */
    fun age(createdAt: Long, now: Long): String {
        val elapsed = (now - createdAt).coerceAtLeast(0L)
        return when {
            elapsed < MINUTE -> "just now"
            elapsed < HOUR -> "${elapsed / MINUTE}m ago"
            elapsed < DAY -> "${elapsed / HOUR}h ago"
            elapsed < WEEK -> "${elapsed / DAY}d ago"
            elapsed < YEAR -> "${elapsed / WEEK}w ago"
            else -> "${elapsed / YEAR}y ago"
        }
    }

    /** How a finished batch reads. Plural-aware, because "1 files" is the sort of thing users notice. */
    fun importedCount(imported: Int): String = when (imported) {
        0 -> "Nothing was added."
        1 -> "1 file added to the vault."
        else -> "$imported files added to the vault."
    }

    /**
     * How many could not be read. Says only that — not why, and not that they were skipped "safely"
     * or anything else we cannot stand behind (hard rule #6).
     */
    fun failedCount(failed: Int): String =
        if (failed == 1) "1 file couldn't be read." else "$failed files couldn't be read."

    private fun oneDecimal(value: Double): String {
        val scaled = (value * 10).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }

    private const val KIB = 1024
    private val UNITS = listOf("KiB", "MiB", "GiB", "TiB")

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
    private const val YEAR = 365 * DAY
}
