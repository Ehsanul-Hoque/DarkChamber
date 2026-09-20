package dev.ehsanfahad.darkchamber.feature.vault.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The running import batch, as something a screen can watch and steer but does not own.
 *
 * It belongs to the **session**, not to a screen (spec D7): navigating away from the gallery — which
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
}
