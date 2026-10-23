package dev.ehsanfahad.darkchamber.feature.camera.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.subscribe
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraSession
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureQueue
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationInfo
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationTitle
import dev.ehsanfahad.darkchamber.feature.camera.domain.MicrophoneHint
import dev.ehsanfahad.darkchamber.feature.camera.domain.ObserveDestination
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * when it stops, so the platform's in-use indicator never outlasts the viewfinder. The captures are
 * the **session's**: [queue], [recorder]'s end-watch and the video lane run in the session's feature
 * scope, so closing the camera lets a photo or a clip finish saving and only a lock abandons one. The
 * video lane and the quality belong to [session], shared by every camera screen of one unlock.
 *
 * Closing while recording keeps the clip: the recording is finalized and handed to the lane, and
 * only then is the camera released and the screen popped ([VideoRecorder.close]).
 *
 * No back callback of its own: there is no in-screen mode for back to leave, so back is the host's
 * pop, like ✕ — which the host routes through [close].
 */
internal class DefaultCameraComponent(
    componentContext: ComponentContext,
    override val camera: CameraController,
    observeDestination: ObserveDestination,
    override val destination: CameraDestination,
    private val session: CameraSession,
    private val queue: CaptureQueue,
    private val recorder: VideoRecorder,
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
            quality = session.quality.value,
            microphone = camera.microphonePermission.value,
        ),
    )
    override val state: Value<CameraState> = _state

    override val tilt: StateFlow<Int> get() = camera.deviceRotation

    // SupervisorJob parented to the caller's Job, as the gallery's is: the host scopes feature work
    // to the vault session, so a lock cancels this before the session it reads through is zeroized.
    private val scope = CoroutineScope(mainContext + SupervisorJob(mainContext[Job]))

    private var binding: Job? = null

    // Whether this screen has raised the system prompt. Once per screen: a start after a stop is the
    // same visit, and asking again there would put the prompt back over an answer just given.
    private var asked = false

    // The same rule for the microphone, asked on the first switch to video rather than on entry: a
    // camera opened for a photo has no business asking for sound.
    private var askedMicrophone = false

    private val controls = MutableStateFlow(Controls())

    private var closed = false

    init {
        session.lane.screenOpen = true
        lifecycle.subscribe(onStart = ::bind, onStop = ::unbind)
        doOnDestroy {
            // Finished, not cancelled: the queue lives in the session's scope so that photos already
            // taken are saved after the screen has gone. This only stops it taking more.
            queue.finish()
            session.lane.screenOpen = false
            scope.cancel()
        }

        val camera = combine(camera.permission, camera.availability, camera.capabilities, camera.microphonePermission, ::CameraReading)
        val video = combine(recorder.state, session.lane.state, session.quality, ::Triple)
        combine(
            observeDestination(destination).onStart<DestinationInfo?> { emit(null) },
            camera,
            queue.state,
            controls,
            video,
        ) { place, reading, queued, controls, (recording, lane, quality) ->
            CameraState(
                destination = place,
                permission = reading.permission,
                availability = reading.availability,
                capabilities = reading.capabilities,
                queue = queued,
                lens = controls.lens,
                flash = controls.flash,
                zoom = controls.zoom,
                mode = controls.mode,
                quality = quality,
                torch = controls.torch,
                recorder = recording,
                lane = lane,
                microphone = reading.microphone,
                hasRecorded = controls.hasRecorded,
            )
        }
            .onEach { state ->
                _state.value = state
                // The album went while recording: stop, keeping the clip — the lane files it unfiled
                // and says where it went. A photo would simply fail; a clip is minutes of someone's
                // time.
                if (state.destination?.title == DestinationTitle.AlbumGone && state.recording && !state.recorder.stopping) {
                    recorder.stop()
                }
            }
            .launchIn(scope)
    }

    /**
     * Open the camera once it may be opened: now if permission is already granted, or the moment it
     * is — the seam's start is a no-op without permission, so a grant arriving later has to be
     * answered. Not asked yet, on the first start, raises the system prompt.
     */
    private fun bind() {
        // Unconditionally first: the seam reads the platform's permissions afresh in its start, and
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
    // left on, or record 4K under an HD chip. After the start: before it, there may be no camera to
    // apply them to. The quality is the session's, the rest this screen's.
    private fun applyControls() {
        val wanted = controls.value
        camera.setMode(wanted.mode)
        camera.setLens(wanted.lens)
        camera.setFlash(wanted.flash)
        camera.setZoom(wanted.zoom)
        camera.setQuality(session.quality.value)
        camera.setTorch(wanted.torch)
    }

    // Also reached on destroy: a lifecycle is stopped before it is destroyed, so a lock tearing the
    // screen down from the foreground releases the camera here — abandoning a recording in progress,
    // which is what a lock does.
    private fun unbind() {
        binding?.cancel()
        binding = null
        camera.stop()
    }

    override fun shoot() {
        if (closed) return
        val shutter = _state.value.shutter
        if (!shutter.enabled) return
        when (shutter.kind) {
            ShutterKind.Photo -> queue.shoot()
            ShutterKind.Record -> if (recorder.start()) controls.update { it.copy(hasRecorded = true) }
            ShutterKind.Stop -> recorder.stop()
        }
    }

    override fun setMode(mode: CaptureMode) {
        if (closed || _state.value.recording || controls.value.mode == mode) return
        // The torch belongs to video: leaving it lit behind a photo viewfinder would be a light on
        // that no control on screen shows.
        controls.update { it.copy(mode = mode, torch = false) }
        camera.setMode(mode)
        camera.setTorch(false)
        if (mode == CaptureMode.Video && !askedMicrophone && camera.microphonePermission.value == MicrophonePermission.NotAsked) {
            askedMicrophone = true
            scope.launch { camera.requestMicrophonePermission() }
        }
    }

    override fun switchQuality() {
        val chip = _state.value.qualityChip ?: return
        session.quality.value = chip.next
        camera.setQuality(chip.next)
    }

    override fun flipLens() {
        if (!_state.value.flipOffered) return
        val lens = if (controls.value.lens == Lens.Back) Lens.Front else Lens.Back
        controls.update { it.copy(lens = lens) }
        camera.setLens(lens)
    }

    override fun cycleFlash() {
        if (_state.value.light !is LightControl.Flash) return
        val flash = when (controls.value.flash) {
            FlashMode.Off -> FlashMode.Auto
            FlashMode.Auto -> FlashMode.On
            FlashMode.On -> FlashMode.Off
        }
        controls.update { it.copy(flash = flash) }
        camera.setFlash(flash)
    }

    override fun toggleTorch() {
        val light = _state.value.light as? LightControl.Torch ?: return
        controls.update { it.copy(torch = !light.on) }
        camera.setTorch(!light.on)
    }

    override fun requestMicrophone() {
        when (_state.value.microphoneHint) {
            MicrophoneHint.Ask -> scope.launch { camera.requestMicrophonePermission() }
            MicrophoneHint.Settings -> camera.openSettings()
            MicrophoneHint.None, null -> Unit
        }
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
        // From here nothing on this screen will be read: a clip that fails after this point goes to
        // the not-saved record, which the next screen shows.
        session.lane.screenOpen = false
        if (!_state.value.recording) {
            onClose()
            return
        }
        // In the session's scope, not this screen's: the screen is what is going. A lock landing
        // while this waits cancels it, and the lock takes the screen down itself.
        session.saveScope.launch {
            recorder.close(release = ::unbind)
            onClose()
        }
    }

    /** What the controls last asked of the camera. The seam does not report these back. */
    private data class Controls(
        val lens: Lens = Lens.Back,
        val flash: FlashMode = FlashMode.Off,
        val zoom: Float = 1f,
        val mode: CaptureMode = CaptureMode.Photo,
        val torch: Boolean = false,
        val hasRecorded: Boolean = false,
    )

    /** What the platform reports, read together. */
    private class CameraReading(
        val permission: CameraPermission,
        val availability: CameraAvailability,
        val capabilities: CameraCapabilities,
        val microphone: MicrophonePermission,
    )
}
