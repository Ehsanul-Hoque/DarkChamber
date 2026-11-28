package dev.ehsanfahad.darkchamber.app.link

import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpener
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import kotlin.coroutines.resume

/**
 * Hands a web address to the app that opens web links — Safari, or the browser the user chose — with
 * `UIApplication.openURL(_:options:completionHandler:)`, which answers whether it opened.
 *
 * No `LSApplicationQueriesSchemes` entry: nothing here asks first whether an app can open it
 * (`canOpenURL`); the answer comes from the open itself. An address `NSURL` will not parse is not
 * opened. [openUrl] is the platform call, swapped in a test so the scheme check can be seen to stop an
 * address before it is reached.
 */
internal class IosLinkOpener(
    private val openUrl: (url: NSURL, answer: (opened: Boolean) -> Unit) -> Unit = { url, answer ->
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = answer)
    },
) : LinkOpener {
    override suspend fun open(address: String): LinkOpened {
        if (!isWebAddress(address)) return LinkOpened.NotOpened
        val url = NSURL.URLWithString(address) ?: return LinkOpened.NotOpened
        return suspendCancellableCoroutine { continuation ->
            openUrl(url) { opened ->
                if (continuation.isActive) continuation.resume(if (opened) LinkOpened.Opened else LinkOpened.NotOpened)
            }
        }
    }
}
