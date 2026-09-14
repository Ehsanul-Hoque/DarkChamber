package dev.ehsanfahad.darkchamber.app.picker

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Launch [contract] and suspend until it answers.
 *
 * Registered against `activityResultRegistry` directly rather than through
 * `registerForActivityResult`: the latter has to be called before the activity starts, and we launch
 * from a live screen. That makes unregistering our job, and it happens on **every** path — the
 * result, and cancellation.
 *
 * Shared by both pickers because this registration dance is where the bugs are, and two copies of it
 * would be two chances to get the teardown subtly different.
 */
internal suspend fun <I, O> FragmentActivity.awaitActivityResult(
    keyPrefix: String,
    contract: ActivityResultContract<I, O>,
    input: I,
): O = suspendCancellableCoroutine { continuation ->
    var launcher: ActivityResultLauncher<I>? = null
    launcher = activityResultRegistry.register(nextPickerRequestKey(keyPrefix), contract) { result: O ->
        launcher?.unregister()
        continuation.resume(result)
    }
    continuation.invokeOnCancellation { launcher.unregister() }
    launcher.launch(input)
}

/**
 * A registry key that is unique among **live** registrations — `register` throws on one already in
 * use, and hashes of anything can repeat.
 *
 * One counter for every picker, so a document pick and a photo pick that overlap cannot collide
 * either. Requests only ever come from the main thread (a picker is presented from a screen), so a
 * plain counter is enough.
 */
internal fun nextPickerRequestKey(prefix: String): String = "$prefix-${requestCounter++}"

private var requestCounter = 0L
