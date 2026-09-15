package dev.ehsanfahad.darkchamber.feature.vault

import com.arkivanov.decompose.ComponentContext
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
import dev.ehsanfahad.darkchamber.feature.vault.data.VaultObjectsItems
import dev.ehsanfahad.darkchamber.feature.vault.domain.ImportCoordinator
import dev.ehsanfahad.darkchamber.feature.vault.domain.ImportFile
import dev.ehsanfahad.darkchamber.feature.vault.domain.ImportQueue
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.presentation.DefaultVaultGalleryComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Build the import batch for one unlocked session. The feature's other construction surface, kept
 * separate from the gallery's on purpose: **a batch outlives any screen**, so it cannot be created
 * by one (spec D7).
 *
 * [scope] must be the session's own feature scope — cancelling it is what makes a lock stop an
 * import, and nothing else should be able to.
 */
fun vaultImports(
    vaultObjects: VaultObjects,
    photoPicker: PhotoPicker,
    filePicker: FilePicker,
    scope: CoroutineScope,
    // Where the encrypt runs. Off the caller's thread in production — the whole payload passes
    // through AES-GCM — and injected so host tests can drive a batch to completion deterministically.
    ioContext: CoroutineContext = Dispatchers.Default,
): VaultImports {
    val items = VaultObjectsItems(vaultObjects)
    return ImportCoordinator(
        photoPicker = photoPicker,
        filePicker = filePicker,
        queue = ImportQueue(ImportFile(vault = items, ioContext = ioContext)),
        scope = scope,
    )
}

/**
 * Build the gallery component. The feature's whole screen-construction surface: use cases, the
 * repository adapter and the component implementation all stay `internal` behind it.
 *
 * [vaultObjects] must come from the **session scope** — it holds the live `VaultSession`, so the
 * component cannot outlive the unlocked subtree that owns it. [imports] likewise, and for a stronger
 * reason: the component only watches and steers the batch, it does not own it.
 *
 * This is a plain factory rather than a Koin module of its own (docs/engineering/PATTERNS.md §1
 * describes the latter): the feature's dependencies are session-scoped, and the app module owns that
 * scope. A Koin module here would either have to hardcode the app's scope qualifier or resolve from
 * the root graph, where a live session must never be registered.
 */
fun vaultGalleryComponent(
    componentContext: ComponentContext,
    vaultObjects: VaultObjects,
    imports: VaultImports,
    mainContext: CoroutineContext = Dispatchers.Main,
): VaultGalleryComponent {
    val items = VaultObjectsItems(vaultObjects)
    return DefaultVaultGalleryComponent(
        componentContext = componentContext,
        observeGallery = ObserveGallery(items),
        imports = imports,
        mainContext = mainContext,
    )
}
