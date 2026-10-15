# Manual verification — browsing the vault

> Scope: finding things. The controls row above the grid (which order, and searching by name), what an
> empty result says, the address crumb naming the listing you are standing on, the badge saying which
> album an item is in, the navigation animation, and the way the gate enters and leaves. A sibling of
> `albums-manual.md` rather than part of it: that one is about what albums *are*, this one is about
> reading a vault that has grown. Run at release, and whenever the listing stage, the browse chrome or
> the navigation animation changes.

## What must be true

These are the properties, independent of how they are checked. A build that fails any of them is not
releasable.

1. **A search never hides a damaged item silently.** An item whose metadata will not open has no name
   to match, so a query cannot include it — and the count of what was left out is stated while the
   search is open. A search that quietly dropped those rows would be a way to make tampering
   invisible (`docs/THREAT_MODEL.md`).
2. **No typed query and no name reaches a log line.** The query is a fragment of a file name the user
   retyped, so it is vault content exactly as the name is.
3. **An empty grid never claims the vault is empty when it is not.** A search that matched nothing, a
   listing that could not be read, an empty album and a genuinely empty vault are four different
   sentences, and saying the wrong one tells somebody their files are gone.
4. **Nothing about ordering or searching claims more than it does.** Name order is a case fold, not
   locale collation; a search covers names on the current listing only; and the finished-import offer
   says what *may* be hidden rather than a number nobody has.
5. **Leaving a screen is one step.** One back press or gesture goes back exactly one screen — never
   two, and never out of the app. Backgrounding the app locks the vault, so a back press that escapes
   costs the session.
6. **Locking is not animated.** The unlocked screens leave at once, because the keys are already gone
   when the gate reports itself locked.

## What is already automated (run these first)

- **`./gradlew :feature:vault:testAndroidHostTest :feature:vault:iosSimulatorArm64Test`** — the four
  orders and their tie-breaks (including the unsigned-byte id comparison the catalog's own order
  uses), name matching with its trimming and case folding, damaged items placed last in a name order
  and counted out of a search, the listing stage keeping one subscription across any number of
  control changes, every piece of browse copy including the absences, and which offer a finished
  import makes.
- **`./gradlew :core:vault:testAndroidHostTest :core:vault:iosSimulatorArm64Test`** — an item's album
  id carried on its listing summary, and one decrypt per album.
- **`./gradlew :app:testAndroidHostTest`** — the crumb for each of the four screens, a rename moving
  it, one pop per back, and that the navigation stack leaves back to the animation.
- **`./gradlew :konsist:test`** — the security invariants.

What remains below needs a device: a real screen to read layout and copy on, a real gesture, and a
real system font scale.

## Build + install

- **Android:** `./gradlew :androidApp:installFossDebug` (device connected, USB debugging on).
- **iOS:** open `iosApp/` in Xcode, run on the simulator or a device.

You will need a vault with enough items that a grid scrolls, at least two albums, several items filed
and several not, and names that differ in case and in leading letter (`alps.jpg`, `Beach.png`,
`tax.pdf`) so an ordering mistake is visible. Screen captures come back blank because screenshot
blocking is on for the whole app; a view-hierarchy dump reads the tree rather than the pixels and
gives exact bounds, which is what the layout checks below need.

The two cautions in `albums-manual.md` about driving this from a terminal apply here unchanged:
confirm which app is in front before reading a dump or sending input, and scroll a listing until it
stops growing before counting it.

## Checklist

Tick each line. A failure in A, F or G is release-blocking.

### A. A search tells the truth (properties 1, 2 and 3)

- [ ] Open the search field. The grid is unchanged until something is typed, and the field says it
      searches names.
- [ ] Type part of one file's name. Only matching items remain, in the order that was already chosen.
- [ ] Type something nothing matches. The message says nothing matched **and** that only names on this
      screen were searched — it does not say the vault, the album or the listing is empty.
- [ ] Clear the search. Every item is back, and no filter is left running.
- [ ] Damage one item's metadata in a throwaway vault, then search. That item is excluded and a line
      under the field says how many could not be read. It does not say why.
- [ ] With the search open and the field empty, nothing is reported as left out.
- [ ] With the field open and **empty**, empty the listing underneath it — in an album, select
      everything and remove it. The message explains the empty album; it must not say that names
      failed to match, because nothing was typed. (A listing can empty under an open field without a
      query ever running, and that sentence used to blame the user's typing for it.)
- [ ] **No query in the logs.** With logging at its most verbose, type several searches, then search
      the device log for what you typed. Nothing — and no file name either.

### B. Ordering

- [ ] The collapsed control names the order the grid is in, so the current order is readable without
      opening the menu.
- [ ] Each of the four orders reorders the grid, and the menu marks the one in force.
- [ ] A name order puts items whose names will not open **last**, in both directions.
- [ ] Items imported in one batch (same second) keep a stable order across order changes and
      re-entries — nothing reshuffles between two visits to the same screen.
- [ ] Set a name order, open an album, come back. The order you chose is still in force on the screen
      you set it on, and the album you opened started at newest-first.

### C. The controls row at size (property 4)

- [ ] At the default font size: the row shows at most two controls, and nothing is clipped.
- [ ] At **1.5×** system font size: still two controls, still nothing clipped or off-screen, on all
      three listings. Record the bounds from a dump rather than judging by eye.
- [ ] The field takes focus when it opens, so the tap that asked for it does not need repeating, and
      the keyboard's done key puts the keyboard away without clearing the query.
- [ ] Both controls are at least 48 dp tall — again from the dump.
- [ ] The row is absent while the vault is opening, on a listing that could not be read, and on an
      album that is no longer there; it is **present** on a search that matched nothing, so the query
      can be cleared.

### D. The crumb

- [ ] The whole vault shows the vault's name alone. The album grid, the unfiled view and an album each
      add their own label.
- [ ] Inside an album, the crumb is that album's name. Rename the album from that screen: the crumb
      follows, with no manual refresh.
- [ ] An album whose name will not open shows the unverifiable wording, not a blank.
- [ ] Give an album a very long name. The crumb truncates and the bar's actions stay on screen — this
      is the tightest row in the app. Check at 1.5× too. Note that an album screen's bar carries only
      the lock action; the albums entry belongs to the whole-vault screen, so that is where the
      two-action case is. **Use two names of very different lengths**: identical crumb bounds for both
      is what proves truncation, where one name alone only shows that it happened to fit.
- [ ] Delete the album you are looking at. The crumb does not keep naming it.

### E. A filed item's album

- [ ] On the whole-vault grid, a filed item shows its album; an unfiled item shows nothing at all.
- [ ] That badge sits over the preview, not over the caption of the row below it.
- [ ] Enter selecting mode and select a filed item. The badge and the selection mark are both visible
      and do not overlap.
- [ ] A long album name truncates inside the badge rather than spanning the tile.
- [ ] Inside an album, no tile carries the badge; the same is true in the unfiled view.
- [ ] Rename an album: the badges on the whole-vault grid follow.
- [ ] The mark beside the name reads as a picture stack rather than a smudge. It is a 24 dp drawing
      shown at half that, so its lines land near a single pixel — the one claim in this file that
      needs looking at rather than measuring (`docs/DESIGN.md` §9 sets icon weight at 2 dp on the
      24 dp grid). If it is too faint, the mark wants its own small-size drawing, not a bigger badge.

### F. Back is one step (property 5)

- [ ] From an album, one back press returns to the album grid — **one** screen, not two.
- [ ] The same from the unfiled view, and from the album grid back to the vault.
- [ ] On Android 13 or later, start a back gesture slowly: the outgoing screen follows your finger,
      and releasing completes it. Cancelling it returns to the screen you were on, still only one
      screen deep.
- [ ] With items selected, back leaves selecting mode rather than navigating — and the app does not
      go to the background, which would lock the vault.
- [ ] Turn the system's reduced-motion setting on. Navigation cross-fades instead of sliding, and
      nothing jumps.

### G. The gate, entering and leaving (property 6)

- [ ] Unlocking fades the vault in.
- [ ] Locking from the bar **snaps**: no fade, no trace of the grid, no previews visible for a moment
      after the lock.
- [ ] Backgrounding the app and returning shows the lock screen, with the same instant transition.
- [ ] With reduced motion on, unlocking is instant rather than faded.

### H. A finished import that the filters would hide (property 4)

- [ ] Set a name order, import a file, and let the batch finish. The summary offers to show newest
      first; taking it reorders the grid and leaves the summary and its counts in place.
- [ ] With a search running, import a file. The summary offers to clear the search, and says what you
      added **may** not be among the results — no count of hidden items.
- [ ] With both a search and a name order, the offer is to clear the search first.
- [ ] On a newest-first, unfiltered grid, no such offer appears.
- [ ] Open the search field but type nothing, then import. No offer — nothing is being filtered.

### I. The import card's two actions

- [ ] After a batch of documents, the dismissal sits where the picker's own buttons were, and
      deleting the originals is a separate row at the bottom of the card.
- [ ] Record both sets of bounds. The destructive control must not be within a finger's width of
      where "add photos" was.
- [ ] The delete row reads as a control, not as another line of the summary message.
- [ ] At 1.5× font size with both the delete row and an import offer present, the card is still whole
      and the grid still scrolls clear of it.

### J. The download that carries on

- [ ] Import a file from a cloud provider that has not cached it. While it is being fetched, the card
      says the download carries on even if the import stops.
- [ ] Tap stop during that fetch. The notice says the file is still downloading, that this cannot be
      cancelled, and that it may use data.
- [ ] Watch the other stages (copying, encrypting, finishing). None of them claims a download is
      running.

### K. At the size a vault actually reaches

Use the **largest** vault available, not a comfortable one — `docs/ARCHITECTURE.md` §5 puts the design
target at thousands of items, and a few hundred is the one scale at which this cannot be seen. The
listing is sorted and narrowed where it is collected, not in the background, so this cost lands on the
frame.

- [ ] Type a query one character at a time. Each keystroke appears without the field stuttering, and
      the grid keeps up with it. Typing latency is the measurement, not scrolling.
- [ ] Change the order with the grid scrolled down. The new order arrives without a visible pause.
- [ ] Say what the vault held. A pass at a few hundred items proves nothing about a few thousand, so
      record the count rather than the verdict alone — and if it struggles, report the count and the
      symptom rather than tuning against a guess.

Judging this by eye is unreliable, and it does not have to be: reset the app's frame statistics, type
a query one character at a time, then read them back. The share of janky frames, the missed vsyncs and
the 99th-percentile frame time are the numbers to record, at two vault sizes so the slope is visible
rather than a single verdict. Scroll the same listing as a control — if scrolling is clean and typing
is not, the cost is in the per-keystroke work rather than in drawing.

## Sign-off

Record device models + OS versions, date, and the app version (`:androidApp:printVersion`). Any
failure in A, F or G is release-blocking; the rest are correctness or UX bugs to fix before release.
