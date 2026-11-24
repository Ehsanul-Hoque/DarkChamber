package dev.ehsanfahad.darkchamber.app.link

import android.app.Activity
import android.content.Intent
import android.net.Uri
import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpener

/**
 * Hands a web address to whichever app opens web links, with a browsable `VIEW` intent started from
 * the activity in front — so it opens as the user's own tap would, in their default browser or the
 * chooser. `CATEGORY_BROWSABLE` limits it to apps that agreed to be opened from a link.
 *
 * No permission and no `<queries>` entry: starting an implicit intent needs neither, and nothing here
 * asks which apps are installed.
 *
 * [front] answers how to start an address from the activity in front ([startBrowsable] on it), or
 * `null` when no activity is in front.
 */
internal class AndroidLinkOpener(private val front: () -> ((address: String) -> Unit)?) : LinkOpener {

    override suspend fun open(address: String): LinkOpened {
        if (!isWebAddress(address)) return LinkOpened.NotOpened
        val start = front() ?: return LinkOpened.NotOpened
        return try {
            start(withLowerCaseScheme(address))
            LinkOpened.Opened
        } catch (_: RuntimeException) {
            // No app opens web links (ActivityNotFoundException), the app that claims it will not be
            // started by others (SecurityException), or the system refused the hand-over itself — an
            // address too large to pass. Each means nothing was opened.
            LinkOpened.NotOpened
        }
    }

    /**
     * Android matches an intent's scheme to apps exactly, and browsers declare theirs in lower case, so
     * `HTTPS://…` would find none. Only the scheme is lowered: the rest is what the reader confirmed.
     */
    private fun withLowerCaseScheme(address: String): String {
        val end = address.indexOf(':')
        return address.substring(0, end).lowercase() + address.substring(end)
    }
}

/** Starts [address] from [activity] as a browsable `VIEW` intent. */
internal fun startBrowsable(activity: Activity, address: String) {
    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addCategory(Intent.CATEGORY_BROWSABLE))
}
