package dev.ehsanfahad.darkchamber.feature.camera

import com.arkivanov.decompose.ComponentContext
import dev.ehsanfahad.darkchamber.core.model.NotSavedReporter
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraSession
import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFiles
import dev.ehsanfahad.darkchamber.feature.camera.api.FreeSpace
import dev.ehsanfahad.darkchamber.feature.camera.data.VaultObjectsCaptures
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureQueue
import dev.ehsanfahad.darkchamber.feature.camera.domain.ObserveDestination
import dev.ehsanfahad.darkchamber.feature.camera.domain.SavePhoto
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoRecorder
import dev.ehsanfahad.darkchamber.feature.camera.presentation.DefaultCameraComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * Build the camera's share of an unlocked session — **once per session**, before the first camera
 * opens. See [CameraSession].
 *
 * [vaultObjects] must come from the **session scope**, like the gallery's: it holds the live
 * `VaultSession`, so nothing built from it can outlive the unlocked subtree that owns it.
 *
 * [saveScope] must be the **session's feature scope, not a screen's**. Photos and clips already taken
 * keep saving after the camera closes, and only a lock — cancelling that scope — abandons them. A
 * scope that died with the screen would drop a capture the user made on purpose.
 *
 * A plain factory rather than a Koin module, for the gallery's reason: every dependency here is
 * session-scoped, and the app module owns that scope.
 */
fun cameraSession(
    vaultObjects: VaultObjects,
    // Where each recording is written: a fresh scratch file in the vault's private tmp/.
    clips: ClipFiles,
    // How much room the vault's volume has, to size each recording before it starts.
    freeSpace: FreeSpace,
    saveScope: CoroutineScope,
    // Where a capture that never reached the vault is reported, to be told after the next unlock or
    // on the next screen. It must outlive the session — a lock is the usual reason — so the host
    // keeps it above the gate.
    notSaved: NotSavedReporter,
    // Where the encrypt runs: a whole photo, or a whole clip, passes through AES-GCM, and the thread
    // it must not run on is the one drawing the viewfinder.
    ioContext: CoroutineContext = Dispatchers.Default,
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
): CameraSession =
    CameraSession(VaultObjectsCaptures(vaultObjects), clips, freeSpace, saveScope, notSaved, ioContext, clock)

/**
 * Build one camera screen's component. The feature's whole construction surface beside
 * [cameraSession]: the queue, the recorder, the use cases and the component implementation all stay
 * `internal` behind it.
 */
fun cameraComponent(
    componentContext: ComponentContext,
    session: CameraSession,
    camera: CameraController,
    // Where captures go and which count the bar shows: the listing the camera was opened from. A
    // construction parameter, like the gallery's filter — it is the screen's identity.
    destination: CameraDestination,
    // The host's sentence for an album name that will not open. Passed in so the wording keeps one
    // home, in the feature that draws every other damaged name.
    unreadableNameLabel: String,
    onClose: () -> Unit,
    mainContext: CoroutineContext = Dispatchers.Main,
): CameraComponent {
    // The rule import follows: one album files into that album, and the whole vault and the unfiled
    // items both file nowhere.
    val albumId = (destination as? CameraDestination.Album)?.id
    return DefaultCameraComponent(
        componentContext = componentContext,
        camera = camera,
        observeDestination = ObserveDestination(session.vault),
        destination = destination,
        session = session,
        queue = CaptureQueue(
            camera = camera,
            save = SavePhoto(session.vault, session.clock),
            albumId = albumId,
            scope = session.saveScope,
            ioContext = session.ioContext,
            notSaved = session.notSaved,
        ),
        recorder = VideoRecorder(
            camera = camera,
            lane = session.lane,
            clips = session.clips,
            freeSpace = session.freeSpace,
            albumId = albumId,
            scope = session.saveScope,
            notSaved = session.notSaved,
            soundWanted = { session.soundWanted.value },
        ),
        unreadableNameLabel = unreadableNameLabel,
        mainContext = mainContext,
        onClose = onClose,
    )
}
