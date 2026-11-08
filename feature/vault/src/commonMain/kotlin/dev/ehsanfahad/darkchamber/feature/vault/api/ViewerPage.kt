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

    /** A kind with no viewer yet — video, audio, PDF, anything else — shown as its [badge]. Nothing was read. */
    data class NotViewable(val badge: String) : ViewerPage

    /** Larger than the viewer reads into memory. Nothing was read. */
    data object TooLarge : ViewerPage

    /** Read and verified, but the platform could not make a picture of it. Not damage. */
    data object CantShow : ViewerPage

    /** Failed its integrity check, or is not a container at all. Nothing of it is shown. */
    data object Unverified : ViewerPage

    /** Written by a newer version of the format; the app needs updating to open it. */
    data object NeedsUpdate : ViewerPage

    /** Would not open, for a reason that names no cause. */
    data object CouldntOpen : ViewerPage
}
