package dev.ehsanfahad.darkchamber.feature.vault.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The running import batch, as something a screen can watch and steer but does not own.
 *
 * It belongs to the **session**, not to a screen: navigating away from the gallery — which
 * the albums cycle introduces — must not cancel an import half way through fifty photos, while
 * locking still must, because locking ends the session.
 *
 * The consequence to be honest about: when a lock ends a batch, this object dies with the session
 * and the user can never be told what happened afterwards. Persisting batch state across a lock is
 * not something we will do, so the notice shown *before* an import is the only warning there is.
 */
interface VaultImports {
    val state: StateFlow<ImportState>

    /** Present the photo library and import what comes back. */
    fun importPhotos()

    /** Present the document picker and import what comes back. */
    fun importFiles()

    /** Stop the batch. What has already been imported stays imported. */
    fun cancel()

    /** Dismiss a finished summary. A running batch is not dismissible. */
    fun acknowledge()

    /**
     * Ask to delete the originals of the files that were just imported. **Deletes nothing** — it
     * opens the disclosure, which is where what deleting does and does not do gets stated.
     *
     * A no-op when the batch left nothing deletable, which is the ordinary case.
     */
    fun requestOriginalDelete()

    /**
     * Delete them, after the disclosure has been shown and answered. Reports how many actually went
     * — a provider can refuse a delete it advertised, and saying otherwise would tell someone a
     * file is gone while it is still there.
     *
     * A no-op unless the disclosure is currently up: it is the authorisation, not a formality.
     */
    fun confirmOriginalDelete()

    /**
     * Decline, explicitly. The other way out of the disclosure, and the only other one — it is not
     * dismissible, because a dialog someone taps past has not told them anything.
     *
     * The offer stays on the summary afterwards: declining is a decision about this dialog, not a
     * decision never to be asked.
     */
    fun keepOriginals()
}
