package dev.ehsanfahad.darkchamber.feature.vault.presentation

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * The one assertion about previews that cannot be wrong: the gallery's loader has **no disk
 * cache**, so no decrypted preview is ever written to storage (hard rule #1).
 *
 * It lives in the iOS source set because that is where a `PlatformContext` exists without a device:
 * on Android the type *is* a `Context`, which a host unit test has no way to produce. The function
 * under test is in `commonMain`, so what runs here is the same code either platform gets. The
 * source-level guard that covers *every* loader, on both platforms and in modules this test knows
 * nothing about, is the Konsist rule.
 */
class VaultImageLoaderTest {
    @Test
    fun the_gallery_loader_has_no_disk_cache() {
        val loader = vaultImageLoader(PlatformContext.INSTANCE, FakeVaultItems())

        assertNull(loader.diskCache, "a decrypted preview must never reach storage")
        assertEquals(
            CachePolicy.DISABLED,
            loader.defaults.diskCachePolicy,
            "and the policy must forbid one being attached later",
        )
    }

    @Test
    fun the_gallery_loader_keeps_its_memory_cache() {
        val loader = vaultImageLoader(PlatformContext.INSTANCE, FakeVaultItems())

        // Without it the grid re-reads, re-decrypts and re-decodes every tile on every scroll.
        // Holding decrypted content in memory is fine while the vault is open; the session's
        // teardown is what makes it fine afterwards.
        assertNotNull(loader.memoryCache, "the grid would re-decrypt every tile on every scroll")
        assertEquals(CachePolicy.ENABLED, loader.defaults.memoryCachePolicy)
    }

    @Test
    fun the_gallery_loader_can_only_fetch_vault_items() {
        val loader = vaultImageLoader(PlatformContext.INSTANCE, FakeVaultItems())

        // The registration is what makes a tile's request reach the encrypted store at all, and it
        // is a single line in a builder chain — exactly the kind of line a refactor drops without
        // any test noticing, since a loader with no fetcher for the model simply draws nothing.
        assertTrue(
            loader.components.fetcherFactories.any { it.first is VaultThumbnailFetcher.Factory },
            "the vault fetcher must be registered, or every tile silently falls back to its badge",
        )
        assertTrue(
            loader.components.keyers.any { it.first is VaultThumbnailKeyer },
            "and the keyer, or the memory cache misses on every scroll",
        )
    }

    @Test
    fun a_plain_builder_comes_with_a_disk_cache_which_is_the_whole_hazard() {
        // Not a test of our code — a record of Coil's, and the reason the loader above is written
        // the way it is. A bare builder attaches a RealDiskCache, so *forgetting* is the dangerous
        // default: any ImageLoader built anywhere else in this app, by anyone, starts out writing
        // what it fetched to storage. Measured, not assumed, and re-measured on every Coil bump.
        val plain = ImageLoader.Builder(PlatformContext.INSTANCE).build()

        assertNotNull(
            plain.diskCache,
            "if Coil ever stops attaching one by default, say so here rather than leaving a stale warning",
        )
    }
}
