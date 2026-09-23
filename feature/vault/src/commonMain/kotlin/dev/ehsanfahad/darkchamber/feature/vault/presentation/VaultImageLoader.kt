package dev.ehsanfahad.darkchamber.feature.vault.presentation

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems

/**
 * The gallery's image loader: **memory cache on, disk cache off, permanently.**
 *
 * ### Why the disk cache is a P0, not a preference
 *
 * Everything this loader is ever handed is decrypted vault content. An image loader's disk cache
 * stores what it fetched — so with one enabled, browsing the grid writes plaintext previews of the
 * user's files into the app's cache directory, where they stay after a lock, after the session's
 * scratch is purged, and after the process dies. That is hard rule #1 ("plaintext never touches
 * shared storage"; decrypted data lives only in memory or in the wiped-on-lock `tmp/`), and it is
 * the worst kind of violation to find late: **the app looks completely correct**. Every tile
 * renders, every test passes, and the only evidence is a directory nothing in the UI mentions.
 *
 * So it is disabled twice over — no cache instance *and* a policy that forbids one — because the
 * two are independently sufficient and each is one line someone could delete.
 *
 * The memory cache stays on: it is what stops the grid re-decrypting the same tile on every scroll.
 * It holds decrypted content, which is fine while the vault is open and **not** fine afterwards, so
 * the session's teardown clears it alongside the scratch purge.
 *
 * ### If you are building another `ImageLoader`
 *
 * Do the same thing, or better, route through here. A loader constructed somewhere else — a viewer
 * screen, a preview dialog — silently reintroduces the whole problem with its defaults. That is why
 * there is a Konsist rule over production sources rather than only a test over this function: a
 * test can only cover the loader it knows about.
 */
internal fun vaultImageLoader(context: PlatformContext, items: VaultItems): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            // The only source this loader has. Everything it draws comes out of the encrypted
            // store, through the vault's own read path — there is no network fetcher and no file
            // fetcher in play (hard rule #3 has already removed the first; the second would be a
            // way to read something that is not vault content).
            add(VaultThumbnailKeyer())
            add(VaultThumbnailFetcher.Factory(items))
        }
        .diskCache(null)
        .diskCachePolicy(CachePolicy.DISABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .build()
