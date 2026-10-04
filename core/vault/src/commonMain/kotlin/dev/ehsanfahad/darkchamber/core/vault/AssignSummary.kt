package dev.ehsanfahad.darkchamber.core.vault

/**
 * What an assignment amounted to: how many items were asked for, and how many were there to move.
 *
 * **[skipped] is not a failure.** An item shredded between being selected and being assigned is an
 * ordinary race — the selection is a list of ids the user chose some seconds ago — and its absence
 * from the album is already the outcome they wanted. The count exists so a caller can say "48 of 50
 * moved" instead of either lying by omission or treating a benign race as an error.
 *
 * It is also the only partial result this operation has. The write itself is a single statement in a
 * transaction, so it cannot half-apply: either every present id moved or none did and the whole thing
 * is an `Err`. What can be partial is **coverage** of the ids asked for, which is what this reports.
 */
data class AssignSummary(val requested: Int, val assigned: Int) {
    /** Ids that were asked for but no longer exist in the catalog. */
    val skipped: Int get() = requested - assigned

    /** True when every id asked for was moved. */
    val complete: Boolean get() = skipped == 0
}
