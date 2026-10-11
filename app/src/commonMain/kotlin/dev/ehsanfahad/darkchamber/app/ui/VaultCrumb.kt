package dev.ehsanfahad.darkchamber.app.ui

import dev.ehsanfahad.darkchamber.app.root.UnlockedChild
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.label

/**
 * What the vault's bar says it is above, or `null` for the vault itself.
 *
 * The bar is hard-coded to one word on every screen, which is fine until there are four of them: the
 * whole vault, one album, the unfiled items and the album grid all look alike from the chrome down,
 * and inside an album the only thing on screen naming it is the heading the grid scrolls away.
 *
 * A function rather than a `when` inside the bar, because nothing can test a branch written into a
 * composable — and three of the five answers here are judgement calls about what may be claimed.
 *
 * **An album's name is decrypted content**, and this is where it crosses from the feature into this
 * module. That is inside the unlocked subtree and so inside the trust boundary — this module already
 * constructs the feature's components with a live session — and it arrives as an [AlbumState], whose
 * name type redacts itself when printed. No new discipline, but the old one holds: **this string is
 * never logged**, and it is read from the screen's live state rather than copied into a navigation
 * config, so nothing persists it.
 *
 * Two of the answers are deliberately nothing at all:
 *
 * - **the album is not there any more** — deleted here or from elsewhere. The screen underneath is
 *   already saying so, and a name in the bar would claim the album still exists;
 * - **the name has not arrived yet** — the album's own listing emits a moment after the screen is
 *   pushed. A placeholder would be a word the user then watches get replaced; the bar simply reads
 *   as the vault until there is something true to add.
 */
internal fun crumbFor(child: UnlockedChild, gallery: GalleryState?): String? =
    when (child) {
        // Named, not blank: this screen is a sibling of the grid rather than a filtered view of it,
        // so without a word here the bar is identical to the one the user just left.
        is UnlockedChild.Albums -> ALBUMS

        is UnlockedChild.Gallery -> when (val listing = child.scope) {
            // The vault *is* the destination, so a second label would repeat the name beside it.
            GalleryScope.WholeVault -> null
            GalleryScope.Unfiled -> UNFILED
            // The one case where the crumb earns its place, and the one that cannot be a constant.
            // Read live, so a rename made from this very screen is reflected rather than stale.
            GalleryScope.Album -> (gallery?.album as? AlbumState.Present)?.name?.label()
        }
    }

/** The same word the entry point that leads here uses, and the one the feature's own copy uses. */
private const val ALBUMS = "Albums"

/** As the delete and filing copy names it — the items in no album, not a place of their own. */
private const val UNFILED = "Unfiled"
