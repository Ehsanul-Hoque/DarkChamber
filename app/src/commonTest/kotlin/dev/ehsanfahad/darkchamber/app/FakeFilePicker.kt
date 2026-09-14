package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile

/**
 * A picker that always cancels. The gate tests build the unlocked subtree — which constructs the
 * gallery — but never drive an import; the real platform pickers live in `androidMain`/`iosMain`.
 */
class FakeFilePicker : FilePicker {
    override suspend fun pickFiles(): List<PickedFile> = emptyList()
}
