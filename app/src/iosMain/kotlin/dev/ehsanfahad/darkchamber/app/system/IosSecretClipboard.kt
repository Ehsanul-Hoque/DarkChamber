package dev.ehsanfahad.darkchamber.app.system

import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionExpirationDate
import platform.UIKit.UIPasteboardOptionLocalOnly
import platform.UniformTypeIdentifiers.UTTypeUTF8PlainText
import kotlin.time.Duration

/**
 * iOS's general pasteboard, with the two options that make it fit to hold a secret.
 *
 * **Local only.** This is the one that matters. A plain `UIPasteboard.general.string = …` is
 * broadcast by Universal Clipboard to every device signed into the same iCloud account — a recovery
 * key copied on a phone becomes readable on a Mac in another room, over the network, without the
 * user doing anything. `UIPasteboardOptionLocalOnly` keeps it on this device.
 *
 * **Expiration date.** iOS clears the pasteboard itself at the given time
 * ([SECRET_CLIP_LIFETIME]), with no process of ours needed — unlike Android, where the clear has to
 * be scheduled and dies with the app.
 *
 * Both are options on `setItems`, which is why this does not use the simpler `string` setter:
 * that API has nowhere to put them.
 */
class IosSecretClipboard(private val lifetime: Duration = SECRET_CLIP_LIFETIME) : SecretClipboard {

    override fun copySecret(text: String) {
        val item = mapOf<Any?, Any?>(UTTypeUTF8PlainText.identifier to text)
        val options = mapOf<Any?, Any?>(
            UIPasteboardOptionLocalOnly to true,
            UIPasteboardOptionExpirationDate to NSDate.dateWithTimeIntervalSinceNow(
                lifetime.inWholeMilliseconds / MILLIS_PER_SECOND,
            ),
        )

        UIPasteboard.generalPasteboard.setItems(listOf(item), options)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
