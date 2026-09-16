package dev.ehsanfahad.darkchamber.app.system

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Android's clipboard, with both of its defaults turned off.
 *
 * **Sensitive flag (API 33+).** Without it the system shows a toast previewing whatever was copied —
 * a recovery key, rendered on screen, in front of whoever is looking — and the keyboard keeps it in
 * clipboard history. `EXTRA_IS_SENSITIVE` suppresses both. On older versions the flag does not
 * exist and neither does the preview toast, so there is nothing to suppress.
 *
 * **Expiry.** Android has no equivalent of iOS's expiration date, so the clear is scheduled here
 * ([SECRET_CLIP_LIFETIME]) and only fires while the app is alive. A process death before it fires
 * leaves the clip in place — stated plainly in `docs/THREAT_MODEL.md` rather than papered over, and
 * the reason the UI says "about a minute" rather than promising a deadline.
 *
 * The clip label is deliberately **empty**: it is visible to other apps and to system clipboard UI,
 * so a label reading "recovery key" would announce what is worth stealing.
 */
class AndroidSecretClipboard(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val lifetime: Duration = SECRET_CLIP_LIFETIME,
) : SecretClipboard {

    private var pendingClear: Job? = null

    override fun copySecret(text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return

        val clip = ClipData.newPlainText(EMPTY_LABEL, text).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }

        try {
            manager.setPrimaryClip(clip)
        } catch (_: Exception) {
            // A refused clipboard write is a failed copy, not a crash — and nothing about it gets
            // logged beyond this comment: the value, and even its length, stay out (hard rule #4).
            return
        }

        // The timestamp of the clip we just set, used to tell later whether the clipboard still
        // holds it. Reading the *description* does not need window focus, unlike reading the clip
        // itself, so this keeps working after the user leaves the app.
        val ours = currentTimestamp(manager)

        pendingClear?.cancel() // one pending clear at a time; a second copy resets the clock
        pendingClear = scope.launch {
            delay(lifetime)
            clearIfStillOurs(manager, ours)
        }
    }

    private fun currentTimestamp(manager: ClipboardManager): Long? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                manager.primaryClipDescription?.timestamp
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

    private fun clearIfStillOurs(manager: ClipboardManager, ours: Long?) {
        if (!shouldClearClip(ours = ours, current = currentTimestamp(manager))) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                manager.setPrimaryClip(ClipData.newPlainText(EMPTY_LABEL, ""))
            }
        } catch (_: Exception) {
            // Best-effort by definition; the clipboard is not ours to guarantee.
        }
    }

    private companion object {
        /** Visible to other apps and to system clipboard UI — so it says nothing. */
        const val EMPTY_LABEL = ""
    }
}

/**
 * Whether the scheduled clear should go ahead, given the clipboard timestamp when we copied
 * ([ours]) and the one there now ([current]).
 *
 * Two rules, and the second is the one worth stating:
 *
 * - **A clip that is no longer ours is left alone.** The user copied something else in the
 *   meantime; deleting it would be destroying their work to tidy up after ourselves.
 * - **When we cannot tell, we clear.** No timestamp is available before API 26, and a read can be
 *   refused. Leaving a recovery key on the clipboard for the rest of the session is the worse of
 *   the two mistakes — the window is a minute, so the chance of discarding someone else's clip is
 *   small and the cost of keeping ours is not.
 */
internal fun shouldClearClip(ours: Long?, current: Long?): Boolean =
    ours == null || current == null || current == ours
