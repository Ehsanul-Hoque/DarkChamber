package dev.ehsanfahad.darkchamber.feature.camera.api

/**
 * Where the camera's photos go, and which count it shows: the listing the camera was opened from.
 *
 * The rule import already follows — one album files into that album, the whole vault and the
 * unfiled items both file nowhere — and the count is the count **of that listing**, so it is the
 * number the user sees again when they close the camera.
 */
sealed interface CameraDestination {
    /** Opened from the whole vault: photos are unfiled, the count is every item. */
    data object AllItems : CameraDestination

    /** Opened from the unfiled items: photos are unfiled, the count is the unfiled items. */
    data object Unfiled : CameraDestination

    /**
     * Opened from one album: photos are filed there, the count is that album's.
     *
     * Not a data class — the generated `equals` would compare [id] by identity, so two destinations
     * over the same album would differ. Equality is by content.
     */
    class Album(val id: ByteArray) : CameraDestination {
        override fun equals(other: Any?): Boolean = other is Album && id.contentEquals(other.id)

        override fun hashCode(): Int = id.contentHashCode()

        /** An album id is opaque, so its presence is all that is printed. */
        override fun toString(): String = "Album(id=${id.size}B)"
    }
}
