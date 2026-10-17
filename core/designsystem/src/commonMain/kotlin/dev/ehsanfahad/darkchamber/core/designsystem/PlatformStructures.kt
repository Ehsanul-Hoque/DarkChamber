package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.runtime.Composable

/**
 * Keep the **window this is composed in** out of the structures the platform hands to other
 * processes for autofill and content capture.
 *
 * **Why this exists at all.** A device settled it rather than an argument: with the Android window's
 * secure flag set — which blocks screenshots — focusing a text field still started an autofill
 * session and sent a fill request to the system's autofill service in another process. Compose marks
 * **every** text field as autofill-relevant (its core text field always publishes a content data
 * type), and a relevant node is populated with that field's own text. The fields in this app hold a
 * fragment of a file name the user retyped, an album's name, or the passphrase — decrypted vault
 * content, or the key to it — and a structure handed to another process is the same class of
 * exposure as writing that text to shared storage (hard rules 1 and 5).
 *
 * **Why it is a composable and not one call at the host.** Each dialog is its own window with its own
 * view root, and a flag set on the activity's window does not reach it: the album-name field in a
 * dialog still appeared in a fill request after the activity's window had been excluded. Composed
 * inside the window it protects, this works for whichever window hosts it, so the rule is "every
 * window that shows vault content calls this" rather than "remember which windows exist".
 *
 * `expect`/`actual` rather than an injected seam, for the same reason [prefersReducedMotion] is: it
 * touches a platform window and needs no host object, and a seam would have to be threaded through
 * every dialog.
 */
@Composable
expect fun ExcludeFromPlatformStructures()
