package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.Filing
import dev.ehsanfahad.darkchamber.feature.vault.api.FilingDestination
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySelection
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the gallery's bottom area shows — one bar at a time — and so whether the camera button is
 * there. The layout branches on [bottomBar] alone, so this is the whole rule.
 */
class GalleryBottomBarTest {

    private val batches: List<ImportState> = listOf(
        ImportState.Idle,
        ImportState.Picking,
        ImportState.PickerUnavailable,
        ImportState.Running(index = 0, total = 2, name = "a.jpg", fileFraction = 0.5f),
        ImportState.Finished(imported = 1, failed = emptyList()),
    )

    private val selections = listOf(
        GallerySelection(),
        GallerySelection(active = true, ids = listOf(byteArrayOf(1))),
    )

    private val filings: List<Filing?> = listOf(
        null,
        Filing.Choosing(),
        Filing.Moving,
        Filing.Moved(requested = 1, moved = 1, destination = FilingDestination.Album),
    )

    /**
     * Every batch state × selection × filing. A move in flight or just reported outranks everything;
     * then selecting; then the batch. The add buttons — and the camera beside them — show only with
     * nothing else claiming the bar, including under the picker-unavailable notice, whose fix is
     * usually to tap again.
     */
    @Test
    fun each_state_gets_exactly_one_bar() {
        for (batch in batches) for (selection in selections) for (filing in filings) {
            val state = GalleryState(loading = false, batch = batch, selection = selection, filing = filing)
            val expected = when {
                filing is Filing.Moving || filing is Filing.Moved -> GalleryBottomBar.FilingReport
                selection.active -> GalleryBottomBar.Selection
                batch is ImportState.Running -> GalleryBottomBar.Progress
                batch == ImportState.Picking -> GalleryBottomBar.Picking
                batch is ImportState.Finished -> GalleryBottomBar.Summary
                batch == ImportState.PickerUnavailable -> GalleryBottomBar.PickerUnavailable
                else -> GalleryBottomBar.Actions
            }
            assertEquals(expected, state.bottomBar(), "$batch / selecting=${selection.active} / $filing")
        }
    }

    /** The camera goes wherever the add buttons go, and nowhere else: one bottom bar at a time. */
    @Test
    fun the_camera_is_offered_exactly_with_the_add_buttons() {
        val offered = GalleryBottomBar.entries.filter { it.offersCamera }.toSet()
        assertEquals(setOf(GalleryBottomBar.Actions, GalleryBottomBar.PickerUnavailable), offered)
    }
}
