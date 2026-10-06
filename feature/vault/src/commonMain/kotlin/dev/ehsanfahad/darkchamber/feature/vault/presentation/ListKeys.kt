package dev.ehsanfahad.darkchamber.feature.vault.presentation

/**
 * A stable list key for an id.
 *
 * **A `ByteArray` cannot be one.** Its equality is identity, so a re-emitted listing carrying the
 * same ids in new arrays would look to a lazy layout like an entirely new list — every tile
 * recreated, every arrival animation replayed. Hex is an identity rather than content: an id is
 * opaque bytes, and lowercase fixed-width per byte means the same id always produces the same
 * string.
 *
 * One copy for every surface that keys a list by an id — the item grid, the album grid and the album
 * chooser all did this, and three identical helpers are three chances for one of them to be written
 * differently.
 */
internal fun ByteArray.toListKey(): String =
    joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        "${HEX_DIGITS[value shr 4]}${HEX_DIGITS[value and 0x0F]}"
    }

private const val HEX_DIGITS = "0123456789abcdef"
