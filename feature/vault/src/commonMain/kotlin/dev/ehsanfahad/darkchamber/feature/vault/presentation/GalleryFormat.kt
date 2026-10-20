package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraftProblem
import dev.ehsanfahad.darkchamber.feature.vault.api.FilingDestination
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
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
     * What the order control reads as, collapsed.
     *
     * The control names the **current** order rather than the action, which is what stops the grid's
     * order being invisible until someone goes looking for it.
     *
     * The two name labels are the Latin alphabet's, matching every file manager a user has met. They
     * are also the limit of what the ordering actually is — a case fold and a code-point comparison,
     * not locale collation — so a vault named in another script is ordered consistently but not the
     * way that script would order it. The label and the comparison are owed the same localization
     * pass, and neither should be presented as having had it.
     */
    fun orderLabel(order: GalleryOrder): String =
        // Exhaustive on purpose: a fifth order must be made to name itself rather than inheriting
        // whichever branch happened to be last.
        when (order) {
            GalleryOrder.Newest -> "Newest"
            GalleryOrder.Oldest -> "Oldest"
            GalleryOrder.NameAsc -> "A–Z"
            GalleryOrder.NameDesc -> "Z–A"
        }

    /**
     * The search field's label, which is also what the affordance that opens it says.
     *
     * "Search names", not "Search": only the name is matched. The MIME type sits in the same
     * decrypted metadata and is deliberately not searched, and date, size and kind are sort keys
     * rather than queries — so a bare "Search" would invite "pdf", or a date, and return nothing
     * with no way to tell that from an empty vault.
     */
    fun searchNames(): String = "Search names"

    /**
     * What the collapsed order control is **called**, as opposed to what it reads.
     *
     * The control shows the order and a caret, which is right on screen and useless spoken: "Newest"
     * announced on its own does not say it is a control, what it controls, or that a list of other
     * orders is behind it. Every aperture element already carries a description for the same reason
     * (docs/DESIGN.md §13) — a drawing that cannot be read aloud needs words somewhere.
     */
    fun orderControlDescription(order: GalleryOrder): String = "Sort order: ${orderLabel(order)}"

    /**
     * The way out of the search field.
     *
     * "Clear search", not "Done": closing **discards the query**, so a label implying the filter is
     * kept would hide the one consequence of pressing it. Closing and clearing are one action because
     * a filter that outlives its field has nothing on screen to explain or undo it.
     */
    fun clearSearch(): String = "Clear search"

    /**
     * What the album mark on a tile is **called**, for a reader rather than an eye.
     *
     * The badge draws a mark and the album's name. Seen together those say "this is in an album
     * called Trip"; announced, the name alone is a stray word between a file name and a size — so
     * the mark carries the relationship and the name stays the name.
     *
     * Unlike the album grid's badge, where the word "Album" is already beside the mark and a reader
     * announcing both would say it twice.
     */
    fun inAlbum(): String = "In album"

    /**
     * Why a just-finished import may not be where the user is looking.
     *
     * **Says "may", and never a number.** A finished batch carries counts and no ids, so this layer
     * cannot know whether a filter is hiding one of the new items, all of them or none — and a
     * sentence like "2 new items are hidden" would be inventing the one fact nobody has. Both lines
     * describe the *screen*, which is knowable, and leave the user to look.
     *
     * The two cases are genuinely different and must not share a sentence: a query can leave an item
     * off the grid entirely, while an order only moves it.
     */
    fun importOfferExplanation(offer: ImportOffer): String =
        when (offer) {
            ImportOffer.ClearSearch -> "These results are filtered, so what you added may not be among them."
            ImportOffer.ShowNewestFirst -> "The grid isn't newest first, so what you added may be further down."
        }

    /** What the offer's one action says. Each names where it leads rather than agreeing with it. */
    fun importOfferAction(offer: ImportOffer): String =
        when (offer) {
            // The same words the search control uses for the same act, because it *is* the same act
            // — a second phrasing would read as a second, different thing.
            ImportOffer.ClearSearch -> clearSearch()
            ImportOffer.ShowNewestFirst -> "Show newest first"
        }

    /**
     * What a search had to leave out, or `null` when it left out nothing.
     *
     * An item whose metadata will not open has no name to match, so a query cannot include it — and
     * the exclusion is **stated**, because a search that silently dropped those rows would be a way
     * to make tampering invisible (docs/THREAT_MODEL.md). The item is still listed everywhere else;
     * this is about results only.
     *
     * It does not say why. A GCM tag mismatch is all that is known, and corruption and tampering are
     * indistinguishable from here, so naming either would be a guess presented as a finding (hard
     * rule #6).
     *
     * Null rather than an empty string, like [noAlbums]: the absence is then testable, and the caller
     * is not left deciding whether a blank line should take up space.
     */
    fun hiddenUnreadable(count: Int): String? =
        when (count) {
            0 -> null
            1 -> "1 item couldn't be read, so it isn't in these results."
            else -> "$count items couldn't be read, so they aren't in these results."
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
     *
     * **And a fourth situation that is none of those: [unreadable], the listing could not be read.**
     * The grid is equally empty then, but all three answers above assert something about what is in
     * the vault, which is the one thing a failed read cannot know — "Nothing in the vault yet" after
     * the index failed to open says the files are gone. The failed answer describes the read instead,
     * is the same whatever the scope, and claims no absence. It is a parameter rather than the caller's
     * choice of string so that the decision sits here, where it can be tested, instead of in a
     * composable nothing can assert on.
     *
     * **And a fifth: [searching], the grid is empty because nothing matched.** All three ordinary
     * answers would be false — a search for "tax" inside a full album must not report the album as
     * empty — so this one talks about the query instead, and is the same whatever the scope for the
     * same reason the failed read is. It also says what a query is matched *against*, because the two
     * ways to get no results that are not a typing mistake are invisible otherwise: names only, and
     * this screen only.
     *
     * **[unreadable] outranks it**, and only in that direction. The query is still in the field when a
     * read fails, so both are true at once, and "nothing matches" would blame someone's typing for a
     * vault that did not open.
     */
    fun emptyListing(scope: GalleryScope, unreadable: Boolean, searching: Boolean = false): String =
        if (unreadable) {
            "Couldn't read what's in your vault."
        } else if (searching) {
            "No names match what you typed.\nSearch covers names on this screen — not dates or file types."
        } else {
            // Exhaustive on purpose: a fourth scope must be made to answer for itself rather than
            // inheriting whichever branch happened to be last.
            when (scope) {
                GalleryScope.WholeVault -> "Nothing in the vault yet.\nAdd a file to encrypt it."
                GalleryScope.Album ->
                    "Nothing in this album yet.\nSelect items in your vault to file them here."
                GalleryScope.Unfiled -> "Everything is filed.\nNothing is waiting to be sorted."
            }
        }

    /**
     * What to say when there are no albums, or `null` to say nothing at all.
     *
     * Says what an album is *for*, because a blank grid teaches nothing — and only that, since the
     * create affordance is on the same screen and does not need describing in prose. And it
     * says what an album is **not**: grouping, not protection. Everything in the vault is already
     * encrypted, and a user who reads "album" as "extra lock" has been misled by omission — the one
     * place this screen could overstate what the app does (hard rule #6).
     *
     * **When the listing could not be read there is nothing honest to put here**, hence the null. "No
     * albums yet" would be an absence nobody established. Unlike the item grid, which needs *some*
     * message because the empty listing is the whole of it, that screen still has the banner saying
     * the read failed and the Unfiled entry leading somewhere true — a second line would be the
     * banner's sentence twice.
     */
    fun noAlbums(unreadable: Boolean): String? =
        if (unreadable) {
            null
        } else {
            "No albums yet.\nAlbums group your files so they are easier to find — everything in the " +
                "vault is encrypted either way."
        }

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
            // Not "nothing changed": what is true, and what the user needs, is that the album still
            // answers to the name it had.
            AlbumDraftProblem.RenameFailed -> "Couldn't rename the album. It still has its old name."
        }

    /**
     * The selection bar's filing action, and the chooser's heading.
     *
     * **"Move to album", never "Add to album".** An item belongs to at most one album, so filing
     * something already filed takes it out of where it was — and a user who read "add" and believes
     * they now hold a second encrypted copy has been misled about what the vault contains (hard rule
     * #6). "Move" is the same length and the truth.
     */
    fun moveToAlbum(): String = "Move to album"

    /**
     * Taking items out of an album.
     *
     * Worded so it **cannot** be read as deletion, which is the risk: this button sits where a shred
     * button will eventually sit, on a screen full of selected files. "Remove" is avoided for the same
     * reason — it is what every other app's delete button says. This names the state the items end up
     * in, which is the one the vault actually has.
     */
    fun unfileSelection(): String = "Take out of album"

    /** The chooser's one-line explanation of what choosing does. */
    fun chooserExplanation(count: Int): String {
        val subject = if (count == 1) "This item" else "These $count items"
        return "$subject will move here. An item is in one album at a time, so anything already in " +
            "another album leaves it."
    }

    /** What the chooser says when there is nothing to choose, which is also when creating is the way on. */
    fun chooserEmpty(): String = "No albums yet. Name one and the selection moves into it."

    /**
     * What came of a move — and when some of it did not happen, it says so.
     *
     * "4 of 5" rather than "4" or "5", following the import's deleted-originals line rather than
     * inventing a third style for the same shape of bad news. The first number alone loses that
     * anything was skipped; the second would tell someone five items are filed when four are.
     *
     * The skipped ones are items that left the vault between being selected and being moved, which is
     * why the reason is stated plainly instead of being guessed at.
     */
    fun movedCount(moved: Int, requested: Int, destination: FilingDestination): String {
        val where = when (destination) {
            FilingDestination.Album -> "moved into the album"
            // Not "removed", and not "deleted": the items are in the vault, outside any album.
            //
            // "The album", never "their album" or "its album": this phrase is shared by every count
            // branch below, so a possessive picked for one of them is wrong in the others — "1 item
            // taken out of their album" is what a plural possessive produces. There is exactly one
            // album to refer to in any case, because taking items out is only offered from inside one.
            FilingDestination.Unfiled -> "taken out of the album"
        }
        return when {
            moved == 0 -> "Nothing was $where."
            moved < requested -> "$moved of $requested $where. The rest are no longer in the vault."
            moved == 1 -> "1 item $where."
            else -> "$moved items $where."
        }
    }

    /** The rename dialog's heading, and the menu entry that opens it. */
    fun renameAlbum(): String = "Rename album"

    /** The other entry in that menu. */
    fun deleteAlbum(): String = "Delete album"

    /**
     * The spoken name of the control those two entries live behind.
     *
     * It names its **subject**, not its shape: three dots are nothing to a screen reader, and "more"
     * on its own does not say more of what. The album is not named here either — the bar above
     * already says which album the screen is, and repeating a decrypted name into a second
     * announcement buys nothing.
     */
    fun albumActions(): String = "More actions for this album"

    /**
     * The delete confirmation's heading and body — **the most load-bearing copy in the album
     * surface**.
     *
     * A user arriving here expects folder semantics: delete the folder, lose what is in it. The
     * opposite happens. An album is a label on an item, not a container that owns it, so deleting one
     * keeps every file and leaves them unfiled. Someone who assumed otherwise would either be
     * terrified of a harmless action or, worse, delete an album *in order* to delete its contents and
     * believe the files were gone.
     *
     * So the body states the outcome first and in plain words, and says where the items go — because
     * "kept" without "where" sends someone hunting an empty album grid for five hundred files.
     *
     * What it must never say (hard rule 6, and the honesty rules in docs/DESIGN.md):
     *
     * - that it frees space — it frees one small row and nothing else;
     * - that it deletes, removes or clears files — it does not touch a single one;
     * - that it is permanent or irreversible in any way that implies data loss. The album is gone for
     *   good; the files are not, and conflating the two is the misreading this dialog exists to stop.
     *
     * The empty case says so plainly rather than reciting the reassurance: there is nothing in it to
     * reassure anyone about, and copy that insists on safety where no risk exists reads as a warning.
     */
    fun deleteAlbumQuestion(): String = "Delete this album?"

    fun deleteAlbumExplanation(itemCount: Int?): String =
        when (itemCount) {
            // Not yet known, which is not the same as zero. The heading this dialog opens from appears
            // as soon as the album does, and that is independent of the item listing's first emission —
            // so "This album is empty" here would be a guess dressed as a fact, in the one dialog whose
            // job is to be exactly right about what is at stake. The rule holds at any size, so it is
            // stated without a number.
            null -> "Everything in this album stays in your vault. It moves to Unfiled, and only the " +
                "album itself is deleted."
            0 -> "This album is empty. Only the album is deleted."
            1 -> "The item in this album stays in your vault. It moves to Unfiled, and only the " +
                "album itself is deleted."
            else -> "The $itemCount items in this album stay in your vault. They move to Unfiled, and " +
                "only the album itself is deleted."
        }

    /**
     * A delete that failed, said without the reassurance the create's failure can honestly give.
     *
     * The vault unfiles the items and then deletes the album row, with no transaction spanning the
     * two. So "nothing changed" **might be false**: interrupted between the writes, the items are
     * already unfiled and the album is still there, holding nothing. The copy says what the user can
     * then see with their own eyes, and tells them the one thing that is certainly still true — the
     * files are all still in the vault.
     */
    fun deleteAlbumFailed(): String =
        "Couldn't delete the album. Everything that was in it is still in your vault, but some of it " +
            "may already have moved to Unfiled."

    /**
     * The album is not there any more — deleted from another screen while this one was open.
     *
     * Says where the items went, for the same reason the confirmation does: the grid in front of the
     * user has just emptied, and an empty grid with no explanation reads as files that are gone.
     */
    fun albumGone(): String =
        "This album isn't in your vault any more.\nAnything that was in it is still there, under Unfiled."

    /** The one way out of that notice. Says where it leads rather than just "OK". */
    fun backToAlbums(): String = "Back to albums"

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

    /**
     * The load-bearing sentence of the import card. A lock or a background ends the batch, and
     * afterwards there is nobody left to tell — warning beforehand is the only chance there is, so
     * it stays visible for the whole import rather than only at the start.
     *
     * **During a fetch it admits what stopping does not stop.** The phone goes on downloading the
     * file regardless, because no API can call a provider's transfer back
     * (`docs/ARCHITECTURE.md`) — and on a metered connection that is the user's data being spent
     * after they believe they have stopped. This is the more important of the two places that is
     * said, because it is the only one a *lock* reaches: after a lock the state is gone and there is
     * nobody to tell.
     *
     * One clause, not a paragraph. The honest point is narrow, and over-explaining a small, rare
     * cost on a progress card would turn it into a warning about the app.
     */
    fun keepOpenNotice(stage: ImportStage?): String =
        "Keep DarkChamber open — locking the vault or leaving the app stops the import." +
            downloadClause(stage, " The download itself carries on either way.")

    /**
     * Shown from the moment Stop is tapped until the batch actually ends.
     *
     * Says a stop *may take a moment* rather than naming what it is waiting for, because that
     * depends on where the stop lands. Waiting for a provider to hand the file over is abandoned at
     * once; part-way through the encrypt the stop takes effect on the next read of the payload,
     * which is kilobytes away but still not instant (`docs/ARCHITECTURE.md` — the stop predicate is
     * the same check a lock uses, and it lands on the next read of the payload). Promising an
     * instant stop would be the opposite error (hard rule 6), so it promises neither. The second
     * half is the part that stops the wait feeling like a loss.
     *
     * **And during a fetch it says the file is still coming**, for the same reason as above: the
     * batch has heard the user, and the transfer has not.
     */
    fun stoppingNotice(stage: ImportStage?): String =
        "Stopping — this may take a moment. Everything already added stays in the vault." +
            downloadClause(stage, " The file is still downloading — that cannot be cancelled, and it may use data.")

    /**
     * The clause, or nothing, depending on whether a transfer is actually running.
     *
     * [ImportStage.FETCHING] is the **only** stage where this is true, which is why the stage is
     * surfaced at all: a stop during the encrypt leaves no transfer running, a streaming provider's
     * transfer ends when the stream closes, and a batch with no stage yet has not reached a file.
     * Saying it at any of those would be a warning about a cost that is not being paid.
     */
    private fun downloadClause(stage: ImportStage?, clause: String): String =
        if (stage == ImportStage.FETCHING) clause else ""

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
     * How many did not make it in. Says only that — not why, and not that they were skipped "safely"
     * or anything else we cannot stand behind (hard rule #6).
     *
     * It used to say "couldn't be read", which named a cause. That was true while an unreadable source
     * was the only way a file could fail; now that an import can be filed into an album as it is
     * created, a destination that disappeared mid-batch fails the file too — and blaming the file for
     * that would send someone looking for corruption that is not there.
     */
    fun failedCount(failed: Int): String =
        if (failed == 1) "1 file wasn't added." else "$failed files weren't added."

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

    /**
     * The spoken words for a preview whose bytes failed their integrity tag — where no name is read
     * out beside it to carry the meaning, as one is under a gallery tile. A failed tag cannot say
     * whether it was tampering or decay, and says nothing about the item's own bytes, so neither is
     * claimed (hard rule #6).
     */
    fun unverifiedPreview(): String = "Preview couldn't be verified"

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
