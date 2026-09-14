package dev.ehsanfahad.darkchamber.app.picker

import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS's photo library as the [PhotoPicker] seam.
 *
 * **No permission, by design.** `PHPickerViewController` runs out of process and hands back only
 * what the user chose, so the app never asks for photo-library access and never sees the rest of
 * the library. Reaching for `PHAsset` instead would mean `NSPhotoLibraryUsageDescription` and a
 * permission prompt — a security-review change, not a convenience (hard rule #7).
 *
 * **No [dev.ehsanfahad.darkchamber.app.system.SystemUiHandoff] either**, unlike the Android side.
 * This picker is presented inside our own window, so the app never resigns active and the
 * auto-lock's background trigger does not fire during a pick. The handoff exists for Android, where
 * both pickers are separate activities that stop ours.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPhotoPicker(private val presenter: () -> UIViewController?) : PhotoPicker {

    /**
     * Strong references to the delegates of presentations still in flight — the same weak-delegate
     * hazard cycle 1 hit with the document picker: `PHPickerViewController.delegate` does not retain
     * our bridge object, and a collected delegate means no callback ever fires and `pickPhotos()`
     * suspends forever.
     */
    private val activeDelegates = mutableSetOf<PhotoPickerDelegate>()

    override suspend fun pickPhotos(): List<PickedFile> {
        val host = presenter() ?: error("no view controller to present the picker from")

        val results = suspendCancellableCoroutine { continuation ->
            val configuration = PHPickerConfiguration().apply {
                // 0 means "as many as the user likes" — the point of this cycle.
                setSelectionLimit(0)
            }
            val controller = PHPickerViewController(configuration = configuration)
            var delegate: PhotoPickerDelegate? = null
            delegate = PhotoPickerDelegate { picked ->
                delegate?.let(activeDelegates::remove)
                controller.dismissViewControllerAnimated(true, null)
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

        // Sequential on purpose: the batch imports one file at a time anyway, and resolving twenty
        // providers at once would ask the system to materialise twenty iCloud assets in parallel.
        return results.mapNotNull { it.itemProvider.resolveToFile() }
    }
}

/**
 * Resolve a picked item to a file we can read, or `null` if it will not resolve — one item that
 * refuses must not cost the user the rest of the selection.
 *
 * Asks for the file **in place** first: an asset already on the device is read where it lies, and
 * nothing of it is written into our container before it is encrypted (hard rule #1). When the
 * system can only give a copy — an iCloud asset it had to download — that copy lives in a temporary
 * directory it deletes as soon as this callback returns, so it is moved somewhere we control and
 * [IosPickedFile.close] deletes it after the import.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun platform.Foundation.NSItemProvider.resolveToFile(): PickedFile? =
    suspendCancellableCoroutine { continuation ->
        loadInPlaceFileRepresentationForTypeIdentifier(UTTypeItem.identifier) { url, isInPlace, _ ->
            continuation.resume(pickedFileFrom(url, isInPlace))
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun pickedFileFrom(url: NSURL?, isInPlace: Boolean): PickedFile? {
    if (url == null) return null

    if (isInPlace) {
        val scoped = url.startAccessingSecurityScopedResource()
        val path = url.path
        if (path == null) {
            if (scoped) url.stopAccessingSecurityScopedResource()
            return null
        }
        return IosPickedFile(url = url, path = path, scoped = scoped)
    }

    // Not in place: the URL points at scratch the system reclaims the moment this returns, so the
    // bytes have to be taken now or not at all. Moved rather than copied — one traversal, and it
    // leaves nothing behind in the system's directory.
    val destination = NSURL.fileURLWithPath(NSTemporaryDirectory() + NSUUID().UUIDString + "-" + (url.lastPathComponent ?: "item"))
    val moved = NSFileManager.defaultManager.moveItemAtURL(url, destination, error = null)
    if (!moved) return null

    val path = destination.path ?: return null
    return IosPickedFile(url = destination, path = path, scoped = false, scratch = destination)
}

/**
 * Bridges the picker's delegate callback into the coroutine. A cancellation arrives as an empty
 * list, which is already the seam's "the user backed out" — there is no separate cancel callback to
 * miss.
 */
private class PhotoPickerDelegate(
    private val onResult: (List<PHPickerResult>) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    private var resumed = false

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        if (resumed) return
        resumed = true
        onResult(didFinishPicking.filterIsInstance<PHPickerResult>())
    }
}
