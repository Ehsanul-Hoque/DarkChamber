package dev.ehsanfahad.darkchamber.app.ui

import dev.ehsanfahad.darkchamber.app.root.NotSavedSummary
import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason

/**
 * The notice's lines, one per reason, in a fixed order. Each names the cause its reason stands for
 * and no other (hard rule 6): the lock, because the code knows a lock abandoned it; a full disk, only
 * because the platform said so at the write; nothing at all for a failure the code cannot explain.
 *
 * What it never says is what or where: no name, no album, because the notice is told after a lock
 * from a record that may hold nothing but counts.
 */
internal fun notSavedLines(summary: NotSavedSummary): List<String> =
    NotSavedReason.entries.mapNotNull { reason ->
        val photos = summary.count(NotSavedKind.Photo, reason)
        val videos = summary.count(NotSavedKind.Video, reason)
        if (photos + videos == 0) return@mapNotNull null
        val things = listOfNotNull(
            counted(photos, "photo"),
            counted(videos, "video"),
        ).joinToString(" and ")
        val one = photos + videos == 1
        when (reason) {
            NotSavedReason.Locked ->
                "$things ${if (one) "wasn't" else "weren't"} saved because the vault locked."
            NotSavedReason.OutOfSpace ->
                "$things ${if (one) "wasn't" else "weren't"} saved — your phone ran out of space."
            NotSavedReason.TooShort ->
                "$things ${if (one) "was" else "were"} too short to save."
            NotSavedReason.CouldNotSave ->
                "$things couldn't be saved."
            NotSavedReason.SavedUnfiled ->
                "$things ${if (one) "was" else "were"} saved to Unfiled — ${if (one) "its" else "their"} album was deleted."
        }
    }

private fun counted(count: Int, noun: String): String? =
    when (count) {
        0 -> null
        1 -> "1 $noun"
        else -> "$count ${noun}s"
    }
