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
    data class Media(val kind: PlayerKind, val note: TrackNote? = null) : ViewerPage

    /** A kind with no viewer yet — video, audio, PDF, anything else — shown as its [badge]. Nothing was read. */
    data class NotViewable(val badge: String) : ViewerPage

    /** Larger than the viewer reads into memory. Nothing was read. */
    data object TooLarge : ViewerPage

    /** Read and verified, but the platform could not make a picture of it. Not damage. */
    data object CantShow : ViewerPage

    /**
     * A video or audio item none of whose tracks this device's decoders play. Not damage: what was read
     * verified, and the file is unchanged in the vault.
     */
    data object CantPlay : ViewerPage

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

/** What a player page plays: a picture with its sound, or sound alone. */
enum class PlayerKind { Video, Audio }

/** What a playing page says is missing: its picture, or its sound. */
enum class TrackNote { NoPicture, NoSound }
