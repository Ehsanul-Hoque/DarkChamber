package dev.ehsanfahad.darkchamber.feature.camera

import com.arkivanov.decompose.ComponentContext
import dev.ehsanfahad.darkchamber.core.model.NotSavedReporter
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import dev.ehsanfahad.darkchamber.feature.camera.data.VaultObjectsCaptures
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureQueue
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureVault
import dev.ehsanfahad.darkchamber.feature.camera.domain.ObserveDestination
import dev.ehsanfahad.darkchamber.feature.camera.domain.SavePhoto
import dev.ehsanfahad.darkchamber.feature.camera.presentation.DefaultCameraComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * Build the camera component. The feature's whole construction surface: the queue, the use cases,
 * the vault adapter and the component implementation all stay `internal` behind it.
 *
 * [vaultObjects] must come from the **session scope**, like the gallery's: it holds the live
 * `VaultSession`, so the camera cannot outlive the unlocked subtree that owns it.
 *
 * [saveScope] must be the **session's feature scope, not the screen's**. Photos already taken keep
 * saving after the camera closes, and only a lock — cancelling that scope — abandons them. A scope
 * that died with the screen would drop a photo the user took on purpose.
 *
 * A plain factory rather than a Koin module, for the gallery's reason: every dependency here is
 * session-scoped, and the app module owns that scope.
 */
fun cameraComponent(
    componentContext: ComponentContext,
    vaultObjects: VaultObjects,
    camera: CameraController,
    // Where photos go and which count the bar shows: the listing the camera was opened from. A
    // construction parameter, like the gallery's filter — it is the screen's identity.
    destination: CameraDestination,
    saveScope: CoroutineScope,
    // Where a photo that never reached the vault is reported, to be told after the next unlock. It
    // must outlive the session — a lock is the usual reason — so the host keeps it above the gate.
    notSaved: NotSavedReporter,
    // The host's sentence for an album name that will not open. Passed in so the wording keeps one
    // home, in the feature that draws every other damaged name.
    unreadableNameLabel: String,
    onClose: () -> Unit,
    mainContext: CoroutineContext = Dispatchers.Main,
    // Where the encrypt runs: the whole photo passes through AES-GCM, and the thread it must not run
    // on is the one drawing the viewfinder.
    ioContext: CoroutineContext = Dispatchers.Default,
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
): CameraComponent =
    cameraComponent(
        componentContext = componentContext,
        vault = VaultObjectsCaptures(vaultObjects),
        camera = camera,
        destination = destination,
        saveScope = saveScope,
        notSaved = notSaved,
        unreadableNameLabel = unreadableNameLabel,
        onClose = onClose,
        mainContext = mainContext,
        ioContext = ioContext,
        clock = clock,
    )

/** The same, over any [CaptureVault] — the seam the tests build through. */
internal fun cameraComponent(
    componentContext: ComponentContext,
    vault: CaptureVault,
    camera: CameraController,
    destination: CameraDestination,
    saveScope: CoroutineScope,
    notSaved: NotSavedReporter,
    unreadableNameLabel: String,
    onClose: () -> Unit,
    mainContext: CoroutineContext,
    ioContext: CoroutineContext,
    clock: () -> Long,
): CameraComponent =
    DefaultCameraComponent(
        componentContext = componentContext,
        camera = camera,
        observeDestination = ObserveDestination(vault),
        destination = destination,
        queue = CaptureQueue(
            camera = camera,
            save = SavePhoto(vault, clock),
            // The rule import follows: one album files into that album, and the whole vault and the
            // unfiled items both file nowhere.
            albumId = (destination as? CameraDestination.Album)?.id,
            scope = saveScope,
            ioContext = ioContext,
            notSaved = notSaved,
        ),
        unreadableNameLabel = unreadableNameLabel,
        mainContext = mainContext,
        onClose = onClose,
    )
