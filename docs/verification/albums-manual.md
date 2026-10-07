# Manual verification — albums

> Scope: the album surface — the album grid, one album's contents, creating, renaming and deleting an
> album, moving selected items in and out of one, and importing into the album being viewed. Verifies
> the properties that only a real device and a real database file can show: that album **names never
> appear in the catalog file**, that deleting an album **keeps every file**, and that counts and covers
> stay current as the vault changes. Run at release, and whenever the album surface, the catalog's
> listing queries or the item-to-album writes change.

## What must be true

These are the properties, independent of how they are checked. A build that fails any of them is not
releasable.

1. **An album name never appears in plaintext anywhere outside memory.** Not in the catalog file, not
   in a log line, not in a crash report. The album row stores a sealed blob; the name exists decrypted
   only while the vault is unlocked.
2. **Deleting an album deletes no files.** Every item that was in it is still in the vault afterwards,
   openable, and now unfiled. The confirmation says so before the fact, and says where the items go.
3. **Nothing in the surface claims an album protects anything.** Everything in the vault is encrypted
   the same way whether or not it is in an album, and no copy, label or icon may suggest that filing
   something adds a lock, a password or a boundary (`docs/THREAT_MODEL.md`; there are no per-album
   keys).
4. **An item is in at most one album.** Filing an item that is already filed moves it: it leaves the
   album it was in. No copy of the file is made, and no wording implies one is.
5. **Counts and covers describe the vault as it is.** Importing into an album, moving items in or out,
   and shredding an item all show up in that album's count and cover without a manual refresh.
6. **A damaged album is visible, not hidden.** An album whose name cannot be verified still appears,
   still holds its items, and can still be deleted. It says the name could not be verified, and does
   not guess whether the cause was tampering or corruption.
7. **The unfiled view is reachable and honest.** Items in no album can always be found, and that entry
   is not presented as an album — it cannot be renamed or deleted.

## What is already automated (run these first)

Everything except the platform-level checks below is proven by fast tests:

- **`./gradlew :core:db:testAndroidHostTest :core:db:iosSimulatorArm64Test`** — the three listing
  cases including the unfiled one, the batched item-to-album write and its row count, and that a
  listing re-emits when the items it summarises change.
- **`./gradlew :core:vault:testAndroidHostTest :core:vault:iosSimulatorArm64Test`** — album names
  sealed and opened, the album listing with counts and covers, creating/renaming/deleting, that a
  rename preserves the row's other columns, that deleting an album unfiles its items **before**
  removing the row (driven by injecting a driver failure between the two writes), and that an import
  can name its destination.
- **`./gradlew :feature:vault:testAndroidHostTest :feature:vault:iosSimulatorArm64Test`** — the album
  tiles and their ordering, the create/rename/delete flows including every refusal, the chooser
  excluding the album the items are already in, partial moves reported with both numbers, and the copy
  rules (no "add", no "remove", no claim that nothing changed when it might have).
- **`./gradlew :app:testAndroidHostTest`** — the album destination reaches the pushed screen, and a
  double tap on an album opens one screen rather than crashing.
- **`./gradlew :konsist:test`** — the security invariants.

What remains below needs a device: a real SQLite file to grep, a real photo library, and a real screen
to read copy on.

## Build + install

- **Android:** `./gradlew :androidApp:installFossDebug` (device connected, USB debugging on).
- **iOS:** open `iosApp/` in Xcode, run on the simulator or a device.

You will need a vault with a handful of imported files. Note that screen captures come back blank
because screenshot blocking is on for the whole app; a view-hierarchy dump reads the tree rather than
the pixels and is usually enough to check a layout, including exact bounds.

Two cautions if any of this is driven from a terminal rather than by hand, both of which have produced
false results here:

- **Confirm which app is in front before reading a dump or sending any input.** A dump that fails
  leaves whatever was written last in place, so the next read returns the *previous* screen and every
  conclusion drawn from it is about a screen that is no longer there. Have the dump fail loudly instead
  of falling back, and check the focused window first. Typing blind is worse than reading blind: a
  passphrase sent to a screen that is no longer the app goes into whatever has focus, such as a
  launcher's search box.
- **Scroll a listing until the set of items stops growing before counting it.** A fixed number of
  swipes under-reports a grid taller than the swipes reach, and two under-reports that happen to agree
  look exactly like "nothing was lost" — which is the one thing section B exists to establish. Checking
  that the whole-vault count equals the unfiled count plus each album's count is a stronger check than
  any single total, because the three listings have to partition the same vault.

## Checklist

Tick each line. A failure in B or F is release-blocking.

### A. The grid

- [ ] A vault with no albums shows an explanation of what albums are for, plus the entry to the
      unfiled items — not a blank screen.
- [ ] Create an album. It appears without a manual refresh, reading as empty rather than as "0 items".
- [ ] Create a second album whose name sorts before the first. The grid is alphabetical, and
      capitalisation does not decide the order.
- [ ] The unfiled entry carries no count, no cover and no "Album" marker, and offers no rename or
      delete.

### B. Deleting an album keeps its files (property 2)

- [ ] Put several items into an album, then delete it. The confirmation states that the items are kept
      and that they move to the unfiled view, **before** you confirm.
- [ ] After confirming, you are returned to the grid, the album is gone, and **every item is still in
      the vault** — count them in the whole-vault listing.
- [ ] Those items are now in the unfiled view, and each one still opens.
- [ ] Nothing in that confirmation said the deletion frees space, removes files, or is permanent in a
      way that implies data loss.

### C. Moving items (properties 4 and 5)

- [ ] Select several items in the whole vault and move them into an album. The chooser explains that
      an item is in one album at a time. The wording is a move, not an addition.
- [ ] After the move, selecting mode ends and the report says how many moved.
- [ ] Open that album: the items are there, the count matches, and the cover is the newest of them.
- [ ] Move one of those items into a *different* album. It leaves the first one — check both counts.
- [ ] Inside an album, take items out of it. They disappear from that listing, the count drops, and
      they are in the unfiled view. Nothing said "delete" or "remove".
- [ ] With items selected inside an album, the chooser does not offer that same album.
- [ ] Select items, start the chooser, then shred one of them from elsewhere if you can — or simply
      select items and clear the selection — and confirm no chooser is left on screen with nothing to
      move.

### D. Importing into an album (property 5)

- [ ] Open an album and import files. They land **in that album**: the album's count rises and they
      appear in its listing.
- [ ] Import from the whole vault instead. Those files are unfiled — they appear in the unfiled view,
      and no album's count changes.
- [ ] Start an import inside an album, navigate back to the grid while it runs, and let it finish.
      Every file still lands in the album the import started from.

### E. Renaming

- [ ] Rename an album. The heading and the grid both show the new name, and its position in the
      alphabetical order updates.
- [ ] Try to rename it to nothing, or to only spaces. It is refused with a message, and the album
      keeps its old name.

### F. The invariants a device can prove (properties 1 and 3)

- [ ] **No album name in the catalog file.** With the vault locked, pull or read the catalog database
      and search its bytes for the names you used. They must not be found, in any encoding. On
      Android: `adb shell run-as <applicationId> strings files/vault/db/catalog.db | grep -i <name>`
      returns nothing (and `cat` piped to `grep -a` if `strings` is unavailable).
- [ ] **No album name in the logs.** With logging at its most verbose, create, rename, delete and file
      into albums, then search the device log for those names. Nothing. Identifiers, sizes and event
      names only.
- [ ] **No claim of protection.** Read every string on the album surface — the grid, an album's
      screen, both dialogs, the chooser, the selection bar, every report. None of them says or implies
      locked, secured, protected, private-er, or password, and no padlock or shield is drawn.
- [ ] A vault with an album, locked and reopened, shows the same albums with the same contents.

### G. A damaged album (property 6)

- [ ] If you can corrupt a single album's sealed name in a throwaway vault, do so: the album still
      appears, says its name could not be verified, still lists its items, and can still be deleted —
      and deleting it still keeps those items.
- [ ] That album offers no rename, since there is no name to start from.

## Sign-off

Record device models + OS versions, date, and the app version (`:androidApp:printVersion`). Any failure
in B or F is release-blocking; the rest are correctness or UX bugs to fix before release.
