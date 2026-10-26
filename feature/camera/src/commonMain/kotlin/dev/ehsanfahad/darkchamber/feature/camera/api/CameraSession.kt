package dev.ehsanfahad.darkchamber.feature.camera.api

import dev.ehsanfahad.darkchamber.core.model.NotSavedReporter
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureVault
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoLane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.CoroutineContext

/**
 * What every camera screen in one unlocked session shares: the place clips are saved from, and the
 * settings a session remembers. The host builds **one per session** and hands it to each camera it
 * opens; a lock destroys the session and this with it.
 *
 * - **The video lane** is here rather than in a screen, so that a camera reopened while a clip is
 *   still encrypting finds the lane busy — one plaintext file at a time across the whole session.
 * - **The quality and the microphone toggle** are remembered for the session, not across a lock (a
 *   lock forgets everything).
 *
 * Opaque to the host: everything it holds is `internal`, built through `cameraSession`.
 */
class CameraSession internal constructor(
    internal val vault: CaptureVault,
    internal val clips: ClipFiles,
    internal val freeSpace: FreeSpace,
    /** The session's feature scope: photos and clips outlive the screen, and a lock cancels them. */
    internal val saveScope: CoroutineScope,
    internal val notSaved: NotSavedReporter,
    internal val ioContext: CoroutineContext,
    internal val clock: () -> Long,
) {
    internal val lane = VideoLane(vault, clock, saveScope, ioContext, notSaved)

    internal val quality = MutableStateFlow(VideoQuality.HD)

    /** Whether recordings should have sound — the microphone toggle. On until turned off, until the lock. */
    internal val soundWanted = MutableStateFlow(true)

    override fun toString(): String = "CameraSession"
}
