package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta

/**
 * A listing as the screen should draw it: the tiles, and how many items a search had to leave out
 * because their names will not open.
 *
 * [hiddenUnreadable] travels with the list rather than being recomputed by the caller, because the
 * two have to agree: a count that disagreed with the list would be a statement about something the
 * screen is not showing, which is the one thing it exists to get right.
 */
internal class GalleryListing(
    val items: List<GalleryItem>,
    val hiddenUnreadable: Int = 0,
)

/**
 * Filter a listing down to the items whose name contains [query].
 *
 * **Case-insensitive, substring, name only.** The MIME type sits in the same decrypted metadata and
 * is deliberately not matched — someone searching "tax" is not asking for every PDF — and the
 * plaintext columns (date, size, kind) are not matched either: those are the ordering control's job,
 * and a filter over them would be a second way to do what sorting already does.
 *
 * **A blank or whitespace-only [query] filters nothing**, so clearing the field is the same code path
 * as never having typed rather than a separate case that could behave differently. Surrounding
 * whitespace is trimmed from a query that does have content, since a trailing space is a typing
 * artefact rather than part of a name; whitespace *inside* a query is part of what was typed.
 *
 * **An item whose metadata will not open is excluded, and counted.** It has no name, so a query
 * genuinely cannot match it — claiming otherwise would answer "name contains tax" with items whose
 * names are unknown. But the exclusion is reported, because a search that silently dropped a damaged
 * item would turn the search field into a way to make tampering invisible (docs/THREAT_MODEL.md; the
 * listing's standing rule is that such an item is mapped, never filtered).
 *
 * The input order is preserved: ordering is applied separately (see [ordered]).
 */
internal fun List<GalleryItem>.matching(query: String): GalleryListing {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return GalleryListing(this)

    val matched = ArrayList<GalleryItem>(size)
    var hidden = 0
    for (item in this) {
        when (val meta = item.meta) {
            is GalleryItemMeta.Readable -> if (meta.name.lowercase().contains(needle)) matched += item
            GalleryItemMeta.Unreadable -> hidden++
        }
    }
    return GalleryListing(matched, hidden)
}
