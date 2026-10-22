package dev.ehsanfahad.darkchamber.app.root

import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import dev.ehsanfahad.darkchamber.core.model.NotSavedReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What did not make it into the vault, held by the session gate rather than inside the unlocked
 * subtree — so a lock, which destroys that subtree, does not destroy the news that it abandoned
 * something. Shown on the next unlocked screen, then cleared.
 *
 * Memory only. Process death drops it, and that is accepted: writing it down would mean persisting
 * something across a lock, and a count is not worth a file the lock would then have to reason about.
 */
class NotSavedRecord : NotSavedReporter {
    private val _summary = MutableStateFlow(NotSavedSummary.Empty)
    val summary: StateFlow<NotSavedSummary> = _summary.asStateFlow()

    override fun report(kind: NotSavedKind, reason: NotSavedReason, count: Int) {
        if (count <= 0) return
        _summary.update { it.plus(kind, reason, count) }
    }

    /** The user has seen it. */
    fun clear() {
        _summary.value = NotSavedSummary.Empty
    }

    fun report(kind: NotSavedKind, reason: NotSavedReason) = report(kind, reason, count = 1)
}

/** An immutable snapshot of the record: a count per kind and reason. */
class NotSavedSummary private constructor(
    private val counts: Map<NotSavedReason, Map<NotSavedKind, Int>>,
) {
    fun isEmpty(): Boolean = counts.isEmpty()

    fun count(kind: NotSavedKind, reason: NotSavedReason): Int = counts[reason]?.get(kind) ?: 0

    fun plus(kind: NotSavedKind, reason: NotSavedReason, count: Int): NotSavedSummary {
        val byKind = counts[reason].orEmpty()
        return NotSavedSummary(counts + (reason to byKind + (kind to (byKind[kind] ?: 0) + count)))
    }

    companion object {
        val Empty = NotSavedSummary(emptyMap())
    }
}
