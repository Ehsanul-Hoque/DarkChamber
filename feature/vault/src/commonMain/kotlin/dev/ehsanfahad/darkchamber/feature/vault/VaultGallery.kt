package dev.ehsanfahad.darkchamber.feature.vault

import com.arkivanov.decompose.ComponentContext
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.data.VaultObjectsItems
import dev.ehsanfahad.darkchamber.feature.vault.domain.ImportFile
import dev.ehsanfahad.darkchamber.feature.vault.domain.ImportQueue
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.presentation.DefaultVaultGalleryComponent
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Build the gallery component. The feature's whole construction surface: use cases, the repository
 * adapter and the component implementation all stay `internal` behind it.
 *
 * [vaultObjects] must come from the **session scope** — it holds the live `VaultSession`, so the
 * component cannot outlive the unlocked subtree that owns it.
 *
 * This is a plain factory rather than a Koin module of its own (docs/engineering/PATTERNS.md §1
 * describes the latter): the feature's dependencies are session-scoped, and the app module owns that
 * scope. A Koin module here would either have to hardcode the app's scope qualifier or resolve from
 * the root graph, where a live session must never be registered.
 */
fun vaultGalleryComponent(
    componentContext: ComponentContext,
    vaultObjects: VaultObjects,
    picker: FilePicker,
    mainContext: CoroutineContext = Dispatchers.Main,
): VaultGalleryComponent {
    val items = VaultObjectsItems(vaultObjects)
    return DefaultVaultGalleryComponent(
        componentContext = componentContext,
        observeGallery = ObserveGallery(items),
        picker = picker,
        runImports = ImportQueue(ImportFile(vault = items)),
        mainContext = mainContext,
    )
}
