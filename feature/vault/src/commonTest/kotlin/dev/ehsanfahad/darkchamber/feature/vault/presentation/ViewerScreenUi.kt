package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerState

/**
 * [ViewerScreen] for a UI test: [state] as given, nothing decoded, no players, and every callback a no-op
 * except the ones a test hands in — so a tap reaching the wrong one shows.
 */
@Composable
internal fun TestViewerScreen(
    state: ViewerState,
    onBack: () -> Unit = {},
    onRequestDelete: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
) {
    val playerActions =
        remember {
            PlayerActions(
                playPause = {},
                seekTo = {},
                skip = {},
                holdSpeed = {},
                releaseSpeed = {},
                setVolume = {},
                volume = { null },
                brightness = { null },
                setBrightness = {},
                chooseSpeed = {},
                switchOrientation = {},
            )
        }
    ViewerScreen(
        state = state,
        pictures = emptyMap(),
        players = emptyMap(),
        pdfPages = emptyMap(),
        texts = emptyMap(),
        playerActions = playerActions,
        now = 0L,
        onToggleFormatted = {},
        onOpenLink = {},
        onPdfViewport = { _, _, _, _ -> },
        onPdfScroll = { _, _ -> },
        onViewport = { _, _ -> },
        onShowPage = {},
        onBack = onBack,
        onRequestDelete = onRequestDelete,
        onConfirmDelete = onConfirmDelete,
        onCancelDelete = onCancelDelete,
        onDismissNotice = {},
    )
}
