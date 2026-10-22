package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.Filing
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState

/**
 * Which bar the gallery's bottom area shows. **One at a time**: the add buttons, a batch's card, the
 * selection bar and a move's report all live in the same place, so a state that showed two would be
 * two sets of controls fighting for one row.
 */
internal enum class GalleryBottomBar(
    /**
     * Whether the camera button is on this bar. It sits with the add buttons, so it goes where they
     * go — a camera opened mid-batch or mid-selection would be a second task started on top of one
     * the screen is still showing.
     */
    val offersCamera: Boolean = false,
) {
    /** A move running, or its report. */
    FilingReport,

    /** Selecting mode. Ahead of any batch: the buttons that would start one are not on screen. */
    Selection,
    Progress,
    Picking,
    Summary,

    /** The picker would not open. The add buttons stay under the notice — tapping again usually works. */
    PickerUnavailable(offersCamera = true),

    /** Nothing else is claiming the bar: the add buttons. */
    Actions(offersCamera = true),
}

/** The one bar to show for this state, in the order the layout has always given them. */
internal fun GalleryState.bottomBar(): GalleryBottomBar {
    val filing = filing
    return when {
        filing is Filing.Moving || filing is Filing.Moved -> GalleryBottomBar.FilingReport
        selection.active -> GalleryBottomBar.Selection
        else -> when (batch) {
            is ImportState.Running -> GalleryBottomBar.Progress
            ImportState.Picking -> GalleryBottomBar.Picking
            is ImportState.Finished -> GalleryBottomBar.Summary
            ImportState.PickerUnavailable -> GalleryBottomBar.PickerUnavailable
            ImportState.Idle -> GalleryBottomBar.Actions
        }
    }
}
