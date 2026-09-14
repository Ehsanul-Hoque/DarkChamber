package dev.ehsanfahad.darkchamber.app.picker

import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS's document picker as the [FilePicker] seam.
 *
 * Opened **in place** rather than `asCopy`: a copy would put a second plaintext copy of the file in
 * our container before we ever encrypt it. Reading the original instead means holding a
 * security-scoped resource, which [PickedFile.close] hands back.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFilePicker(private val presenter: () -> UIViewController?) : FilePicker {

    /**
     * Strong references to the delegates of presentations still in flight.
     *
     * `UIDocumentPickerViewController.delegate` is a **weak** property, so the controller does not
     * keep our bridge object alive. Without this set the delegate can be collected between
     * presenting the picker and the user tapping, after which neither callback fires: `pickFiles()`
     * suspends forever and the caller sits mid-import with no way out but a lock.
     *
     * A set rather than one field so overlapping picks cannot evict each other's delegate. Only ever
     * touched on the main thread — presentation and UIKit callbacks both run there.
     */
    private val activeDelegates = mutableSetOf<PickerDelegate>()

    override suspend fun pickFiles(): List<PickedFile> {
        val host = presenter() ?: error("no view controller to present the picker from")

        val urls = suspendCancellableCoroutine { continuation ->
            val controller = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem))
            controller.allowsMultipleSelection = true
            var delegate: PickerDelegate? = null
            delegate = PickerDelegate { picked ->
                // Released once the picker has spoken; UIKit zeroes its weak reference, so a stray
                // later message finds nil rather than a dangling object.
                delegate?.let(activeDelegates::remove)
                continuation.resume(picked)
            }
            activeDelegates += delegate
            controller.delegate = delegate
            continuation.invokeOnCancellation {
                activeDelegates.remove(delegate)
                controller.dismissViewControllerAnimated(true, null)
            }
            host.presentViewController(controller, animated = true, completion = null)
        }

        // A URL we cannot open is dropped rather than failing the pick: one odd file among twenty
        // must not cost the user the other nineteen. Its scoped access is handed straight back.
        return urls.mapNotNull { url ->
            val scoped = url.startAccessingSecurityScopedResource()
            val path = url.path
            if (path == null) {
                if (scoped) url.stopAccessingSecurityScopedResource()
                null
            } else {
                IosPickedFile(url = url, path = path, scoped = scoped)
            }
        }
    }
}

/**
 * Bridges the picker's delegate callbacks into the coroutine. Both outcomes — a pick and a
 * cancellation — resume exactly once; an empty list means cancelled, which the import treats as an
 * ordinary outcome rather than a failure.
 */
private class PickerDelegate(private val onResult: (List<NSURL>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    private var resumed = false

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        deliver(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        deliver(emptyList())
    }

    private fun deliver(urls: List<NSURL>) {
        if (resumed) return
        resumed = true
        onResult(urls)
    }
}
