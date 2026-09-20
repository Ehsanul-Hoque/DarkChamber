package dev.ehsanfahad.darkchamber.app.system

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Put a secret on the system clipboard — as briefly and as locally as the platform allows.
 *
 * **A plain clipboard write is not an acceptable implementation of this interface.** Both platforms
 * do something by default that is worse than it looks, and the whole reason this seam exists rather
 * than Compose's common `LocalClipboard` is to force both defaults off:
 *
 * - **iOS** syncs the general pasteboard to every device signed into the same iCloud account
 *   (Universal Clipboard). A recovery key copied on a phone would appear on a Mac the user may not
 *   even be near. An implementation must mark the write local-only.
 * - **Android 13+** shows a system toast previewing what was copied, and the keyboard keeps a
 *   clipboard history. An implementation must flag the clip sensitive so neither happens.
 *
 * Neither platform makes the clipboard *safe* — any app can read it, and on Android before 13
 * anything in the foreground can. What the flags buy is narrower: the secret does not travel to
 * other devices, is not rendered on screen by the OS, and does not linger. UI copy must not promise
 * more than that (hard rule #6), and `docs/THREAT_MODEL.md` says what it does and does not cover.
 *
 * Implementations must **never log the value, its length, or anything derived from it** — a stage
 * label at most (hard rule #4).
 */
fun interface SecretClipboard {
    /**
     * Copy [text], flagged as sensitive and set to expire on its own where the platform supports it.
     *
     * Best-effort by nature: a platform may refuse, and the caller's confirmation copy has to be
     * honest about a clipboard it does not control. Must not throw.
     */
    fun copySecret(text: String)
}

/**
 * How long a copied secret is meant to survive — one number, both platforms, so the UI's "about a
 * minute" stays true of each.
 *
 * Long enough to switch to a password manager and paste; short enough that a phone left on a desk
 * is not still holding a recovery key. iOS enforces it itself; Android has no such facility and its
 * implementation schedules the clear.
 */
internal val SECRET_CLIP_LIFETIME: Duration = 60.seconds
