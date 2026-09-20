package dev.ehsanfahad.darkchamber.app.picker

import dev.ehsanfahad.darkchamber.app.FakePickedFile
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rule behind [resumeOrClose]: a file resolved for a caller who is no longer there belongs to
 * nobody, so whoever resolved it has to release it.
 *
 * On iOS the picker's completion handler fires whether or not the batch that asked for it is still
 * alive, and by then the file already holds a security-scoped grant and possibly a scratch copy.
 */
class PickedFileHandoffTest {

    @Test
    fun a_file_resolved_for_a_waiting_caller_is_handed_over_still_open() = runTest {
        val file = FakePickedFile(name = "photo.jpg")
        var resume: ((PickedFile?) -> Unit)? = null

        val resolved = async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { continuation: CancellableContinuation<PickedFile?> ->
                resume = { continuation.resumeOrClose(it) }
            }
        }
        resume!!(file)

        assertSame(file, resolved.await())
        assertFalse(file.closed, "the caller now owns the file and closes it itself")
    }

    @Test
    fun a_file_resolved_after_the_caller_was_cancelled_is_closed() = runTest {
        val file = FakePickedFile(name = "photo.jpg")
        var resume: ((PickedFile?) -> Unit)? = null

        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { continuation: CancellableContinuation<PickedFile?> ->
                resume = { continuation.resumeOrClose(it) }
            }
        }
        // A lock mid-pick: the batch's job dies while the system is still materialising the asset.
        waiting.cancel()
        resume!!(file)

        assertTrue(file.closed, "nobody will ever be handed this file, so it has to be released here")
    }

    @Test
    fun nothing_to_resolve_resumes_with_null() = runTest {
        var resume: ((PickedFile?) -> Unit)? = null

        val resolved = async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { continuation: CancellableContinuation<PickedFile?> ->
                resume = { continuation.resumeOrClose(it) }
            }
        }
        resume!!(null)

        assertNull(resolved.await(), "one item that will not resolve must not cost the rest of the pick")
    }
}
