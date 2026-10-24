package dev.ehsanfahad.darkchamber.app.thumbnail

/** How far into a video its preview frame is taken: a second, past the black a camera's first frames often are. */
internal const val PREVIEW_FRAME_US: Long = 1_000_000

/**
 * When the preview frame is taken: [PREVIEW_FRAME_US] in, or the first frame of a clip no longer than
 * that — and of one whose length the file does not say.
 */
internal fun previewFrameTimeUs(durationMs: Long?): Long =
    if (durationMs != null && durationMs * 1_000 > PREVIEW_FRAME_US) PREVIEW_FRAME_US else 0
