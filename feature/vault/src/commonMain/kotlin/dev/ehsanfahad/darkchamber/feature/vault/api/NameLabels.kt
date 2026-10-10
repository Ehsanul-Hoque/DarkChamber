package dev.ehsanfahad.darkchamber.feature.vault.api

/**
 * How a name reads when it will not open — **the one place that sentence is written**.
 *
 * It had been spelled out at four call sites across two screens, and the next screen to draw an
 * album's name would have made five, the first of them outside this module. That is the kind of
 * duplication that goes wrong quietly: the wording is a claim bounded by `docs/THREAT_MODEL.md`, and
 * four copies are four places for one of them to drift into saying more than the vault knows.
 *
 * What it must never say, however the phrasing is revised: that the data was **tampered with**, or
 * that it was **corrupted**. A failed GCM tag is all that is known, and the two are indistinguishable
 * from here — naming either would be a guess presented as a finding (hard rule 6). "Can't be
 * verified" says exactly what happened and stops.
 *
 * Public rather than internal because the app module draws an album's name too, in the bar above
 * this feature's screens. The *colour* stays with each call site: the honest wording is one decision,
 * while how loudly a title, a list row or a tile caption says it is a layout one.
 */
private const val UNVERIFIABLE = "Can't be verified"

/** An album's name, or the honest absence of one — never a guess at why it would not open. */
fun AlbumTileName.label(): String =
    when (this) {
        is AlbumTileName.Readable -> value
        AlbumTileName.Unreadable -> UNVERIFIABLE
    }

/**
 * An item's name, or the honest absence of one.
 *
 * The same sentence an album gets, deliberately: a user who has seen it on a tile should not have to
 * learn a second phrase for the same condition one screen over.
 */
fun GalleryItemMeta.label(): String =
    when (this) {
        is GalleryItemMeta.Readable -> name
        GalleryItemMeta.Unreadable -> UNVERIFIABLE
    }
