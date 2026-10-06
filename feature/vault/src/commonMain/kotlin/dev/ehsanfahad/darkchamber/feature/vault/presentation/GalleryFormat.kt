package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraftProblem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportStage

/**
 * Pure formatting for a gallery tile. Kept out of the composable so it can be tested directly, and
 * so the rules — what a size reads as, how coarse an age is — live in one place.
 */
internal object GalleryFormat {

    /**
     * A short type badge for the media slot, standing in for the thumbnail until thumbnails exist.
     * Derived from the MIME family, so an unknown type still gets something honest rather than a
     * guess at the content.
     */
    fun typeBadge(mime: String): String =
        when {
            mime.startsWith("image/") -> "IMG"
            mime.startsWith("video/") -> "VID"
            mime.startsWith("audio/") -> "AUD"
            mime == "application/pdf" -> "PDF"
            mime.startsWith("text/") -> "TXT"
            else -> "FILE"
        }

    /**
     * What to say when a listing has nothing in it. **Three situations, three answers**, because an
     * empty grid is not one state:
     *
     * - A vault with no files is a new vault, and what it needs is to be told how to put something in.
     * - An album with nothing in it is a perfectly ordinary album; the user filed nothing here yet,
     *   and the way things get filed is from the item grid, so that is what it says.
     * - Nothing unfiled means everything *has* been filed, which is a success rather than an absence
     *   and should not read as one.
     *
     * Saying the first of these on an empty album would tell a user with five hundred files that
     * their vault is empty.
     */
    fun emptyListing(scope: GalleryScope): String =
        when (scope) {
            GalleryScope.WholeVault -> "Nothing in the vault yet.\nAdd a file to encrypt it."
            GalleryScope.Album -> "Nothing in this album yet.\nSelect items in your vault to file them here."
            GalleryScope.Unfiled -> "Everything is filed.\nNothing is waiting to be sorted."
        }

    /**
     * What to say when there are no albums.
     *
     * Says what an album is *for*, because a blank grid teaches nothing — and only that, since the
     * create affordance is on the same screen and does not need describing in prose. And it
     * says what an album is **not**: grouping, not protection. Everything in the vault is already
     * encrypted, and a user who reads "album" as "extra lock" has been misled by omission — the one
     * place this screen could overstate what the app does (hard rule #6).
     */
    fun noAlbums(): String =
        "No albums yet.\nAlbums group your files so they are easier to find — everything in the vault " +
            "is encrypted either way."

    /**
     * What the create affordance and its dialog say.
     *
     * "New album" in both places, deliberately the same words: a button and the dialog it opens that
     * disagree about what is happening make the user check which one they are in.
     */
    fun newAlbum(): String = "New album"

    /** The field's label. Says what is being named, since the dialog's title is the only other clue. */
    fun albumNameLabel(): String = "Album name"

    /**
     * Why a draft is not an album yet, said next to the field rather than as a banner.
     *
     * Both lines say what the state of the world is, because that is the question someone staring at
     * a dialog that did not close actually has:
     *
     * - A missing name is not a failure and must not read as one — nothing was attempted, and the fix
     *   is one word in the field above.
     * - A refused create says **nothing was created**, which is true: the vault seals the name before
     *   it writes the row, so a create that fails leaves no half-made album behind. Saying so is what
     *   stops the user hunting the grid for something that might be there (hard rule 6).
     */
    fun albumDraftProblem(problem: AlbumDraftProblem): String =
        when (problem) {
            AlbumDraftProblem.NameMissing -> "Give the album a name first."
            AlbumDraftProblem.CreateFailed -> "Couldn't create the album. Nothing was created — try again."
        }

    /**
     * What an album tile says under its name.
     *
     * A count and nothing more. **No wording that implies an album protects anything** — no "locked",
     * no "secured", no padlock to caption: an album organises, and a separate security boundary is
     * something this app does not have (hard rule #6). "Empty" rather than "0 items" for the same
     * reason the selection count avoids zero — a fresh album is the ordinary state of one just
     * created, and it should read as a fact rather than a fault.
     */
    fun albumSubtitle(itemCount: Int): String =
        when (itemCount) {
            0 -> "Empty"
            1 -> "1 item"
            else -> "$itemCount items"
        }

    /**
     * How many items are selected.
     *
     * Says "None selected" rather than "0 selected", because zero is a state the user can arrive at
     * without asking: an item selected a moment ago can leave the listing — shredded, or moved out
     * of the album being viewed — and the selection is intersected with what is on screen. The mode
     * stays, so the bar has to read sensibly with nothing in it.
     */
    fun selectedCount(count: Int): String =
        when (count) {
            0 -> "None selected"
            1 -> "1 selected"
            else -> "$count selected"
        }

    /**
     * What to say about the stage an import is in, or **null** when there is nothing worth saying.
     *
     * Two of the four stages get no label. The encrypt is the ordinary case and the card's heading
     * already says which file of how many, so a line repeating it would appear on every import and
     * inform nobody. A null stage is presentation declining to name one yet, because it has not
     * lasted long enough to be worth a word.
     *
     * The wait is **not** called "Downloading". We cannot tell a download from a provider that is
     * merely slow, and naming the wrong one is the kind of small lie hard rule 6 exists to prevent —
     * while "waiting for the file" is true in either case and still explains the stall, which is the
     * whole reason the stage is surfaced at all.
     */
    fun stageLabel(stage: ImportStage?): String? =
        when (stage) {
            ImportStage.FETCHING -> "Waiting for the file…"
            ImportStage.COPYING -> "Copying it in…"
            ImportStage.FINISHING -> "Finishing…"
            ImportStage.ENCRYPTING, null -> null
        }

    /** Binary units, one decimal above a kibibyte — the sizes a file manager would show. */
    fun size(bytes: Long): String {
        if (bytes < KIB) return "$bytes B"
        var value = bytes.toDouble() / KIB
        for (unit in UNITS) {
            if (value < KIB) return "${oneDecimal(value)} $unit"
            value /= KIB
        }
        return "${oneDecimal(value * KIB)} ${UNITS.last()}"
    }

    /**
     * A coarse age. Deliberately imprecise: the exact second a file entered the vault is not
     * information a listing needs to broadcast, and coarse labels stay readable as a vault ages.
     * A [createdAt] in the future (a clock that moved backwards) reads as "just now" rather than a
     * negative age.
     */
    fun age(createdAt: Long, now: Long): String {
        val elapsed = (now - createdAt).coerceAtLeast(0L)
        return when {
            elapsed < MINUTE -> "just now"
            elapsed < HOUR -> "${elapsed / MINUTE}m ago"
            elapsed < DAY -> "${elapsed / HOUR}h ago"
            elapsed < WEEK -> "${elapsed / DAY}d ago"
            elapsed < YEAR -> "${elapsed / WEEK}w ago"
            else -> "${elapsed / YEAR}y ago"
        }
    }

    /** How a finished batch reads. Plural-aware, because "1 files" is the sort of thing users notice. */
    fun importedCount(imported: Int): String = when (imported) {
        0 -> "Nothing was added."
        1 -> "1 file added to the vault."
        else -> "$imported files added to the vault."
    }

    /**
     * How many could not be read. Says only that — not why, and not that they were skipped "safely"
     * or anything else we cannot stand behind (hard rule #6).
     */
    fun failedCount(failed: Int): String =
        if (failed == 1) "1 file couldn't be read." else "$failed files couldn't be read."

    /** The action on the summary. Says how many, because "Delete originals" hides the scale. */
    fun deleteOriginals(count: Int): String =
        if (count == 1) "Delete original" else "Delete $count originals"

    /**
     * What came of the deletion — and when a provider refused some, it says so.
     *
     * "2 of 3" rather than "2" or "3": the first number alone loses that anything was refused, and
     * the second would tell someone a file is gone while it is still sitting in their Downloads.
     */
    fun deletedOriginals(deleted: Int, requested: Int): String = when {
        deleted == 0 -> "No originals were deleted."
        deleted < requested -> "$deleted of $requested originals deleted."
        deleted == 1 -> "1 original deleted."
        else -> "$deleted originals deleted."
    }

    /**
     * The disclosure. Non-dismissable on screen, because this is the only place the limits of
     * deleting are stated — a dialog someone taps past has told them nothing.
     *
     * Every clause is doing work, and none of it is reassurance:
     *
     * - The vault's copy is encrypted, which is the question anyone has at this dialog: am I about
     *   to lose this?
     * - We **ask** the system. Deleting an original is `DocumentsContract.deleteDocument` on a
     *   provider that may say no — the app does not do the deleting and cannot promise the outcome.
     * - Wear-levelling. A flash controller writes where it likes and the filesystem never addresses
     *   the old blocks again; a deleted file's bytes can survive on the chip. Saying so is the same
     *   discipline DESIGN §11 imposes on the shredder — "reduces the chance of recovery", never
     *   "permanently erases" (`docs/THREAT_MODEL.md` out-of-scope item 3, hard rule #6).
     */
    fun deleteOriginalsDisclosure(count: Int): String {
        val subject = if (count == 1) "this file" else "these $count files"
        return "The vault has its own encrypted copy of $subject. " +
            "DarkChamber will ask the system to delete the original. " +
            "That reduces the chance of recovery, but cannot rule it out: storage hardware moves " +
            "data around as it wears, and copies can survive where no app can reach them."
    }

    private fun oneDecimal(value: Double): String {
        val scaled = (value * 10).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }

    private const val KIB = 1024
    private val UNITS = listOf("KiB", "MiB", "GiB", "TiB")

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
    private const val YEAR = 365 * DAY
}
