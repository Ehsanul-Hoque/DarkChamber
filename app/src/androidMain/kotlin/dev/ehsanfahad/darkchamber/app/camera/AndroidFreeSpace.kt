package dev.ehsanfahad.darkchamber.app.camera

import android.content.Context
import android.os.StatFs
import dev.ehsanfahad.darkchamber.feature.camera.api.FreeSpace

/**
 * Free space on the volume the vault lives on, by `StatFs` over the app's files directory — the
 * vault root is inside it, so it is the same volume. The space available to this app, not the
 * volume's raw free count, since that is what a recording can actually fill.
 *
 * `null` when the platform cannot say, which refuses to record rather than guess.
 */
internal class AndroidFreeSpace(private val context: Context) : FreeSpace {
    override fun availableBytes(): Long? = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()
}
