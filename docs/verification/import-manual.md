# Manual verification — file import, gallery, and screen protection

> Scope: the vault gallery and single-file import. Verifies the two platform **file pickers**, the
> import round-trip through a real vault, and the **screen protection** — the parts the automated
> tests deliberately cannot reach (a real document picker, a real screenshot attempt, a real
> app-switcher snapshot). Run at release, and whenever the pickers, the import path, or the host
> screen-protection code change.

## What is already automated (run these first)

Everything except platform UI and screen capture is proven by fast tests before you touch a device:

- **`./gradlew :feature:vault:testAndroidHostTest :feature:vault:iosSimulatorArm64Test`** — the
  listing mapping (a damaged item is listed, not dropped), the import use case (cancel is not an
  error, the picked file is closed on every path), the component's state machine including the
  double-tap guard and a failing listing surfacing an error rather than an empty vault, and the
  size/age/badge formatting.
- **`./gradlew :core:vault:testAndroidHostTest :core:vault:iosSimulatorArm64Test`** — the import
  round-trip, and the one-shot-source spill: byte-identical import, no scratch left behind on
  success or failure, no spill when the source re-opens.
- **`./gradlew :app:testAndroidHostTest :app:iosSimulatorArm64Test`** — the unlocked subtree opens on
  the gallery and locking disposes the scope the gallery was built from.
- **`./gradlew :konsist:test`** — the security invariants, including that no module imports a
  networking API.

The `FilePicker` implementations are **compile-gated only**: a real document picker needs a device.

## Build + install

- **Android:** `./gradlew :androidApp:installFossDebug` (device connected, USB debugging on).
- **iOS:** open `iosApp/` in Xcode, run on the simulator or a device.

Start from a fresh install (or clear app data) so the first run creates a vault.

## Checklist

Tick each line. A failure here is a release blocker.

### Import — Android

- [ ] Create a vault, save the recovery key, unlock. The gallery shows the empty state, **not** a
      spinner that never resolves.
- [ ] Tap **Add file**, pick an image. The picker opens; picking returns to the gallery and a tile
      appears with the right name, a readable size and a recent age.
- [ ] Tap **Add file** and press back without choosing. Nothing is imported and **no error banner
      appears** — cancelling is not a failure.
- [ ] Double-tap **Add file** quickly. Exactly one picker opens.
- [ ] Import a large file (≥ 200 MB video). It completes, and the tile's size matches the original.
- [ ] Import a file from a cloud provider (Drive / OneDrive) if one is installed. It completes —
      this is the path that exercises the one-shot-source spill.
- [ ] After each import, the app-private `tmp/` directory is empty:
      `adb shell run-as dev.ehsanfahad.darkchamber ls files/vault/tmp` → no output.

### Import — iOS

- [ ] Same four checks: empty state, pick an image, cancel with no error, double-tap opens one picker.
- [ ] Import a file from **iCloud Drive**. It completes (this exercises the security-scoped path).
- [ ] Import a large file (≥ 200 MB). It completes and the size matches.
- [ ] **Pick and cancel five times in a row, unhurried** (leave the picker open a few seconds each
      time). Every attempt returns to the gallery. The document picker's delegate is held weakly by
      UIKit, so a lost strong reference shows up as a picker that dismisses with the button stuck on
      "Importing…" — and because collection timing varies, a single successful pick does not prove
      the reference is held.

### Batch import (both platforms)

Run these against the **photo picker** and then repeat the first two against **Add files**, since the
two pickers reach the same queue by different platform APIs.

- [ ] Select **20 photos in one gesture**. All 20 import, the count shown matches what you picked,
      and the gallery holds 20 more tiles when it finishes.
- [ ] The progress reports **both levels**: a file count that advances ("12 of 20"), and for each
      file either a bar that fills once or an indeterminate indicator — never a bar that fills,
      resets and fills again (the container reads each file twice; the UI must not show that twice).
- [ ] Include **one file from a cloud provider** (Drive / iCloud Drive) in a batch. It completes —
      this is the one-shot source that exercises the spill path, now inside a batch.
- [ ] Include a file you have **no permission to read** if you can arrange one. The batch finishes
      the others and reports honestly how many could not be read; it does **not** stop at the failure.
- [ ] The notice **"Keep DarkChamber open — locking the vault or leaving the app stops the import"**
      is visible for the whole import, not just at the start.
- [ ] **Cancel mid-batch** while a large video is encrypting. It stops within a second or two, not
      at the end of that file; already-imported items stay in the gallery; the app-private `tmp/` is
      empty afterwards.
- [ ] **Lock mid-batch** (tap Lock, or background the app). After unlocking, every item imported
      before the lock is present and intact, and there is no half-written item: `objects/` holds only
      complete `.dchm` files, no catalog row lacks a blob, and `tmp/` is empty.
- [ ] New tiles arrive **newest-first, at the top of the grid, and visibly animate in** as each
      import commits — you can tell which tile just landed without counting.
- [ ] Turn on the OS reduced-motion setting (Android: Settings → Accessibility → Remove animations;
      iOS: Settings → Accessibility → Motion → Reduce Motion), reopen the app and import again. New
      tiles **fade** in and the existing tiles **do not slide** — the arrival is still visible, just
      not moving.

### Persistence

- [ ] Import a file, then **kill the app** (swipe away / stop from Xcode) and relaunch. The gate
      shows Locked; after unlocking, the imported tile is still there with the same name and size.
- [ ] Lock manually, unlock again: the gallery repopulates.

### Plaintext never lands (Android)

- [ ] After importing, the object store holds only `.dchm` files:
      `adb shell run-as dev.ehsanfahad.darkchamber find files/vault/objects -type f` — every path
      ends in `.dchm`.
- [ ] Dump one and confirm it is not the original: `... cat <one .dchm> | head -c 16 | xxd` starts
      with the `DCHM` magic, and the file is **not** byte-identical to the imported original.
- [ ] `files/vault/tmp` is empty, and stays empty after a lock.
- [ ] Kill the app mid-import of a large cloud-provider file (the spill path), then relaunch and
      reach the lock screen. `files/vault/tmp` is empty again — a cold open purges scratch that no
      lock was ever there to clean up.
- [ ] Nothing appears in shared storage: the imported file has **not** been copied anywhere under
      `/sdcard`, and the vault does not appear in the system Files app or the gallery app.

### Screen protection — Android

- [ ] Attempt a screenshot on the gallery. The system reports it is blocked by the app, and no image
      is saved.
- [ ] Start a screen recording. The app's content does not appear in the recording.
- [ ] Open the app switcher. The DarkChamber card is blank, showing no vault content.

### Screen protection — iOS

- [ ] Open the app switcher. The DarkChamber card is **blurred**, showing no filenames or tiles.
- [ ] Return to the app. The blur is removed and the gallery renders normally.
- [ ] Background and foreground the app several times in a row. The blur is added and removed each
      time, and never gets stuck on screen.
- [ ] **Expected, not a bug:** a screenshot on iOS still succeeds and captures the screen. iOS has no
      `FLAG_SECURE` equivalent — `docs/THREAT_MODEL.md` states this, and no UI copy claims otherwise.
      Confirm no in-app text promises screenshot protection on iOS.

### Damaged items

Hard to stage on a device; the automated tests cover the mapping. If you want the visual check, use a
debug build to corrupt one row's `enc_meta` and confirm the tile renders in the danger tone reading
"Can't be verified", still shows its size and date, and does **not** silently disappear.

## Record the run

Note the date, the OS versions, the devices, and any deviation, in the release checklist entry for
the build being verified.
