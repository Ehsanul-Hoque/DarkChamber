package dev.ehsanfahad.darkchamber.feature.camera.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.subscribe
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureQueue
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationInfo
import dev.ehsanfahad.darkchamber.feature.camera.domain.ObserveDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The camera screen's state holder, in the gallery's style: [Value] on a lifecycle-scoped scope,
 * gone with the unlocked subtree on lock.
 *
 * Two lifetimes meet here. The camera itself is the **screen's**: open while it is started, released
 * when it stops, so the platform's in-use indicator never outlasts the viewfinder. The photos are the
 * **session's**: [queue] runs in the session's feature scope, so closing the camera only finishes it
 * and a photo already taken is still saved — only a lock abandons one.
 *
 * No back callback of its own: there is no in-screen mode for back to leave, so back is the host's
 * pop, like ✕.
 */
internal class DefaultCameraComponent(
    componentContext: ComponentContext,
    override val camera: CameraController,
    observeDestination: ObserveDestination,
    override val destination: CameraDestination,
    private val queue: CaptureQueue,
    override val unreadableNameLabel: String,
    mainContext: CoroutineContext,
    private val onClose: () -> Unit,
) : CameraComponent(), ComponentContext by componentContext {

    private val _state = MutableValue(
        CameraState(
            destination = null,
            permission = camera.permission.value,
            availability = camera.availability.value,
            capabilities = camera.capabilities.value,
            queue = queue.state.value,
        ),
    )
    override val state: Value<CameraState> = _state

    // SupervisorJob parented to the caller's Job, as the gallery's is: the host scopes feature work
    // to the vault session, so a lock cancels this before the session it reads through is zeroized.
    private val scope = CoroutineScope(mainContext + SupervisorJob(mainContext[Job]))

    private var binding: Job? = null

    // Whether this screen has raised the system prompt. Once per screen: a start after a stop is the
    // same visit, and asking again there would put the prompt back over an answer just given.
    private var asked = false

    private val controls = MutableStateFlow(Controls())

    private var closed = false

    init {
        lifecycle.subscribe(onStart = ::bind, onStop = ::unbind)
        doOnDestroy {
            // Finished, not cancelled: the queue lives in the session's scope so that photos already
            // taken are saved after the screen has gone. This only stops it taking more.
            queue.finish()
            scope.cancel()
        }

        combine(
            observeDestination(destination).onStart<DestinationInfo?> { emit(null) },
            camera.permission,
            camera.availability,
            camera.capabilities,
            combine(queue.state, controls, ::Pair),
        ) { place, permission, availability, capabilities, (queued, controls) ->
            CameraState(
                destination = place,
                permission = permission,
                availability = availability,
                capabilities = capabilities,
                queue = queued,
                lens = controls.lens,
                flash = controls.flash,
                zoom = controls.zoom,
            )
        }
            .onEach { _state.value = it }
            .launchIn(scope)
    }

    /**
     * Open the camera once it may be opened: now if permission is already granted, or the moment it
     * is — the seam's start is a no-op without permission, so a grant arriving later has to be
     * answered. Not asked yet, on the first start, raises the system prompt.
     */
    private fun bind() {
        // Unconditionally first: the seam reads the platform's permission afresh in its start, and
        // that is the only way a change made in the system's settings is ever seen.
        camera.start()
        binding = camera.permission
            .onEach {
                if (it == CameraPermission.Granted) {
                    camera.start()
                    applyControls()
                }
            }
            .launchIn(scope)
        if (!asked && camera.permission.value == CameraPermission.NotAsked) {
            asked = true
            scope.launch { camera.requestPermission() }
        }
    }

    // The platform's camera is one for the app and keeps what the last screen set, so this screen's
    // own settings are put back on every open — otherwise it would draw "Flash: off" over a flash
    // left on. After the start: before it, there may be no camera to apply them to.
    private fun applyControls() {
        val wanted = controls.value
        camera.setLens(wanted.lens)
        camera.setFlash(wanted.flash)
        camera.setZoom(wanted.zoom)
    }

    // Also reached on destroy: a lifecycle is stopped before it is destroyed, so a lock tearing the
    // screen down from the foreground releases the camera here.
    private fun unbind() {
        binding?.cancel()
        binding = null
        camera.stop()
    }

    override fun shoot() {
        if (!_state.value.shutterEnabled) return
        queue.shoot()
    }

    override fun flipLens() {
        if (!_state.value.flipOffered) return
        val lens = if (controls.value.lens == Lens.Back) Lens.Front else Lens.Back
        controls.update { it.copy(lens = lens) }
        camera.setLens(lens)
    }

    override fun cycleFlash() {
        if (!_state.value.flashOffered) return
        val flash = when (controls.value.flash) {
            FlashMode.Off -> FlashMode.Auto
            FlashMode.Auto -> FlashMode.On
            FlashMode.On -> FlashMode.Off
        }
        controls.update { it.copy(flash = flash) }
        camera.setFlash(flash)
    }

    override fun zoomTo(ratio: Float) {
        val capabilities = _state.value.capabilities
        val zoom = ratio.coerceIn(capabilities.minZoom, capabilities.maxZoom)
        controls.update { it.copy(zoom = zoom) }
        camera.setZoom(zoom)
    }

    // Kept inside the viewfinder: a tap on its very edge can land a hair outside it, and the seam's
    // contract is fractions of the preview.
    override fun focusAt(x: Float, y: Float) = camera.focusAt(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

    override fun requestPermission() {
        scope.launch { camera.requestPermission() }
    }

    override fun openSettings() = camera.openSettings()

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }

    /** What the controls last asked of the camera. The seam does not report these back. */
    private data class Controls(
        val lens: Lens = Lens.Back,
        val flash: FlashMode = FlashMode.Off,
        val zoom: Float = 1f,
    )
}
