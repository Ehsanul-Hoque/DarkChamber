package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation

/** One thing to tell the platform's display. */
internal sealed interface DisplayCommand {
    data class Orientation(
        val orientation: ScreenOrientation,
    ) : DisplayCommand

    /** `null` hands the brightness back to the system. */
    data class Brightness(
        val level: Float?,
    ) : DisplayCommand

    /** Hold the screen awake, or let it sleep again. */
    data class KeepScreenOn(
        val on: Boolean,
    ) : DisplayCommand
}

/**
 * When the viewer changes the screen and when it puts it back. Each event answers with the commands to
 * send, in order — nothing when nothing changes.
 *
 * - **Orientation** follows the phone until the button on a video page is pressed; a press holds the
 *   shape the screen is not in. The hold **applies on video pages only**: an image or audio page — which
 *   has no button — follows the app's own orientation, so nobody is left held sideways with no way back;
 *   swiping back to a video page applies the hold again.
 * - **Brightness** set by the slide lasts while its video page is shown: leaving that page hands it back
 *   to the system, and so does closing the viewer or a lock.
 * - **The screen stays on** while the page shown plays — video or audio, since a screen that sleeps
 *   backgrounds the app and the vault locks — and is let sleep when it pauses, ends or stops.
 * - **Closing** (and a lock, which closes the viewer with the rest) puts back all of it.
 */
internal class DisplayRules {
    private var closed = false
    private var onVideo = false

    /** The orientation chosen with the button, or `null` before it is first pressed. */
    private var choice: ScreenOrientation? = null

    /** The orientation last asked of the platform. */
    private var applied = ScreenOrientation.AppDefault

    /** Whether the brightness is the slide's rather than the system's. */
    private var brightened = false

    /** Whether the screen is being held awake. */
    private var awake = false

    /** Whether the viewer was last measured wider than tall; `false` until it is measured. */
    private var landscapeNow = false

    /**
     * The orientation the button would switch to now, which its label names: the shape the screen is
     * **not** in. Read from the screen, never from the last press, because the phone's own auto-rotate
     * turns the screen too — a label naming where the screen already is makes a press that changes
     * nothing and still holds the screen there.
     */
    val buttonTarget: ScreenOrientation
        get() = if (landscapeNow) ScreenOrientation.Portrait else ScreenOrientation.Landscape

    /** The viewer was measured: [landscape] when it is wider than it is tall. Changes nothing itself. */
    fun screenShaped(landscape: Boolean) {
        landscapeNow = landscape
    }

    /** The page shown changed; [video] says whether it is a video page. */
    fun pageShown(video: Boolean): List<DisplayCommand> {
        if (closed) return emptyList()
        val commands = mutableListOf<DisplayCommand>()
        handBackBrightness(commands)
        onVideo = video
        applyOrientation(commands)
        return commands
    }

    /** The orientation button was pressed. Only a video page has one. */
    fun orientationPressed(): List<DisplayCommand> {
        if (!onVideo) return emptyList() // closing leaves no page shown
        choice = buttonTarget
        return mutableListOf<DisplayCommand>().also(::applyOrientation)
    }

    /** The brightness slide moved to [level], on a video page. */
    fun brightnessSlid(level: Float): List<DisplayCommand> {
        if (!onVideo) return emptyList() // closing leaves no page shown
        brightened = true
        return listOf(DisplayCommand.Brightness(level))
    }

    /** The page shown started or stopped playing. */
    fun playbackChanged(playing: Boolean): List<DisplayCommand> {
        if (closed || playing == awake) return emptyList()
        awake = playing
        return listOf(DisplayCommand.KeepScreenOn(playing))
    }

    /** The viewer is going: closed, or taken down by a lock. */
    fun closed(): List<DisplayCommand> {
        if (closed) return emptyList()
        val commands = mutableListOf<DisplayCommand>()
        handBackBrightness(commands)
        onVideo = false
        applyOrientation(commands)
        if (awake) {
            awake = false
            commands += DisplayCommand.KeepScreenOn(false)
        }
        closed = true
        return commands
    }

    private fun handBackBrightness(commands: MutableList<DisplayCommand>) {
        if (!brightened) return
        brightened = false
        commands += DisplayCommand.Brightness(null)
    }

    private fun applyOrientation(commands: MutableList<DisplayCommand>) {
        val wanted = if (onVideo) choice ?: ScreenOrientation.AppDefault else ScreenOrientation.AppDefault
        if (wanted == applied) return
        applied = wanted
        commands += DisplayCommand.Orientation(wanted)
    }
}
