package dev.ehsanfahad.darkchamber.app.link

import android.app.Activity
import android.content.ActivityNotFoundException
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
 * asks which apps are installed. `ActivityNotFoundException` is how Android says no app can.
 */
internal class AndroidLinkOpener(private val activity: () -> Activity?) : LinkOpener {

    override suspend fun open(address: String): LinkOpened {
        if (!isWebAddress(address)) return LinkOpened.NotOpened
        val front = activity() ?: return LinkOpened.NotOpened
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address)).addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            front.startActivity(intent)
            LinkOpened.Opened
        } catch (_: ActivityNotFoundException) {
            LinkOpened.NotOpened
        } catch (_: SecurityException) {
            // An app that claims the link but will not be started by others: nothing was opened.
            LinkOpened.NotOpened
        }
    }
}
