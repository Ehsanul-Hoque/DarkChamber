package dev.ehsanfahad.darkchamber.app.picker

import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume

/**
 * Hands [file] to whoever is waiting, and closes it if nobody is.
 *
 * A picker's completion handler fires on the system's schedule, not ours: the vault can lock — and
 * the batch's job die — while the system is still materialising the asset. Resuming an already
 * cancelled continuation is a silent no-op, so the plain `resume(file)` would drop a file that has
 * already taken a security-scoped grant and, on the copy path, already written a scratch file.
 * Nobody would ever be handed it, so nobody would ever close it: the grant is never handed back (an
 * unbalanced `startAccessingSecurityScopedResource` is a real leak, not a no-op) and the scratch
 * copy outlives the lock that was supposed to wipe it (hard rule #1).
 *
 * `null` needs none of this — there is nothing to release, and one item that will not resolve must
 * not cost the user the rest of the selection.
 */
internal fun CancellableContinuation<PickedFile?>.resumeOrClose(file: PickedFile?) {
    if (file == null) {
        resume(null)
    } else {
        resume(file) { _, _, _ -> file.close() }
    }
}
