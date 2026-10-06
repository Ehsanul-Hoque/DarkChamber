package dev.ehsanfahad.darkchamber.feature.vault.domain

/**
 * What the surface does with a name a user typed, in one place.
 *
 * Creating and renaming ask the same question of the same text, and the vault asks neither: it takes
 * a usable name as a precondition and throws otherwise. Two copies of this rule would be two things
 * to keep in agreement, and the one that drifted would be the one that let a blank name through to a
 * `require`.
 *
 * Surrounding whitespace is **removed**, not merely tolerated: `"Holiday "` and `"Holiday"` are
 * indistinguishable on screen while sorting apart in a list ordered by name. A name of nothing but
 * whitespace therefore reduces to nothing, and nothing is not a name.
 */
internal fun usableAlbumName(typed: String): String? = typed.trim().ifEmpty { null }
