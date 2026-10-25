# Manual verification — the private camera (photos and video)

> Scope: the in-app camera — opening it from a listing, taking photos and recording videos, where
> they land and what the bar counts, the permission and availability states, and what happens when
> the camera is closed, the app is backgrounded or the vault locks with captures still on their way.
> Verifies the properties that only a real camera, a real photo library and a real screen can show:
> that **a capture never reaches shared storage**, that **a video's one scratch file is gone however
> the recording ends**, that a capture carries **no location**, that it is **upright**, and that the
> camera is **released** whenever the screen is not in front. Run at release, and whenever the
> capture path, the save queue, the video lane, the platform camera code or the manifest changes.

## What must be true

These are the properties, independent of how they are checked. A build that fails any of them is not
releasable.

1. **A shot never reaches shared storage.** No new item in the system photo library, `DCIM`, or any
   folder another app can read, after a single shot or a burst. The vault's own scratch directory is
   empty afterwards too: a photo goes from memory into the encrypted object with no file on the way.
2. **A stored shot carries no location.** The app holds no location permission, so no position can be
   attached. Orientation is present; GPS is not.
3. **A stored shot is upright.** Taken at each of the four ways the phone can be held, on each lens,
   it displays the way the scene looked — the orientation is written into the photo before it is
   encrypted, and no pixel is re-encoded.
4. **Nothing on the camera screen leaves the app as an image on Android.** The viewfinder and the
   last-shot tile are covered by screenshot blocking and by the app-switcher protection, like every
   other screen. iOS cannot block screenshots (`docs/THREAT_MODEL.md`); there only the app-switcher
   snapshot is covered.
5. **The camera is released whenever its screen is not in front.** The platform's camera indicator
   goes off when the camera is closed, when the app goes to the background, and when the vault locks.
6. **Asking for the camera does not lock the vault.** The system's permission prompt is not a trip to
   the background; the user returns from it to the camera, still unlocked.
7. **A photo lands where the camera was opened, and the count follows it.** From one album, into that
   album; from the whole vault or the unfiled items, into no album. The bar's count ticks only once the
   photo is in.
8. **A lock never waits for a save, and leaves no half of one behind.** Photos still on their way when
   the vault locks are abandoned: after unlocking there is no partial item, no orphan object and nothing
   in scratch. Closing the camera is **not** a lock — a photo taken just before closing is saved.
9. **The screen claims nothing it cannot keep.** No copy says the camera is silent (some regions force
   a shutter sound on iOS), that photos never leave the vault (they will be exportable), that a
   shot's memory is wiped (only the app's own copies are cleared; the platform's buffers are not the
   app's to clear), or that a video is never stored unencrypted or never touches storage (it passes
   through one scratch file).
10. **The app still installs and works on a device with no camera or no microphone.** Both are
    declared not required; without a camera the camera screen says it is not available, and without
    a microphone videos record without sound.
11. **A video's plaintext lives in one app-private scratch file, and only for as long as it must.**
    While a clip is recorded and encrypted there is exactly one file in the vault's scratch directory
    and none anywhere another app can read. It is gone once the clip is saved, fails, is too short, or
    is abandoned by a lock; after a crash or a forced stop it is gone at the next launch. No video
    reaches the system's media store or photo library.
12. **A recording is never lost without a word, and never kept broken.** A clip that is kept is whole
    and plays; one that is not is reported — on the camera screen while it is open, otherwise on the
    next unlocked screen — with the reason the app actually knows.
13. **Closing the camera finishes the recording.** Back or ✕ while recording keeps the clip whole, and
    the camera is released only after that.
14. **Space is checked before it runs out.** Too little free space refuses to start and says so;
    a recording that reaches its limit stops by itself, is kept, and says why it stopped. Saving a
    clip stopped at the limit fits, unless something else fills the phone meanwhile — and then the
    clip is reported as not saved for lack of space, never left behind in scratch.
15. **Sound only when allowed.** With the microphone refused, or sound turned off before recording, a
    clip has no audio track, and the screen shows that it is recording without sound.
16. **The viewfinder shows exactly the frame that is saved**, at the capture's own shape — 4:3 for a
    photo, 16:9 for a video — with nothing cropped. The screen stays upright while the phone turns,
    its icons turn with the phone, and every capture is stored the way the scene was held.
17. **The camera draws edge to edge without hiding anything.** Only the black and the viewfinder sit
    under the system bars; no control does, with gesture navigation or with buttons.
18. **A video's preview is upright, and costs no file.** A recorded clip and an imported one both get
    a frame from about a second in, the right way up, and making it writes nothing outside the
    scratch directory.

## What is already automated (run these first)

Everything except the device checks below is proven by fast tests:

- **`./gradlew :core:db:testAndroidHostTest :core:vault:testAndroidHostTest`** — the filtered item
  counts for the whole vault, the unfiled items and one album, re-emitting as items move, and that a
  count opens nothing however many items and emissions there are; that an import from a re-openable
  source never spills to scratch.
- **`./gradlew :feature:camera:testAndroidHostTest :feature:camera:iosSimulatorArm64Test`** — the
  orientation patch against fixtures produced by an external tool (tag rewritten in place, a block
  inserted where there is none, malformed input left alone); the save path's arguments and that its own
  arrays are zeroed after success, failure and a lock; the queue's order, bound and failure handling,
  and a lock mid-queue; every permission, availability and failure state's screen decision and words;
  the component's lifecycle — open only while started, asked once, released on stop and destroy, a
  taken photo saved after close — and, against a real vault, that a photo is filed in the album it was
  taken in, raises that count, and that a lock mid-queue leaves no item, object or scratch file.
- **`./gradlew :feature:vault:testAndroidHostTest`** — when the gallery offers the camera button, and
  that a tap landing after the bar has changed opens nothing.
- **`./gradlew :app:testAndroidHostTest :app:iosSimulatorArm64Test`** — each listing opens the camera
  on itself; a double tap opens one camera; closing returns to the same listing; a lock releases the
  camera; closing with a save in flight lets it finish; the platform rules (permission state,
  availability, rotation, flash, the JPEG copy) and that each platform module binds a real camera.
- **`./gradlew :konsist:test`** — the security invariants.
- **Video, `:feature:camera`** (same tasks as above) — the recording handle's one-end rule; the size
  limit and floor from free space, and refusal when it is unknown; the lane's one clip at a time, its
  file deleted on every exit including a lock mid-encrypt, a disk filled mid-save, an album deleted
  mid-save (saved unfiled) and a crash followed by the launch purge, against a real vault; the
  recorder's close waiting for the finish and abandoning on timeout; every recording state's screen
  decision and words.
- **Video, `:app`** (same tasks) — a lock while recording, mid-encrypt, and with photos held, through
  the real teardown, reaching the not-saved notice with the right counts; the platform rules (how each
  recorder's end becomes the app's, the microphone permission, the tilt, the qualities per lens, the
  capture shape per mode); the preview reader's random reads over a stream and which frame is taken.
  The iOS frame grabber runs over real clips on the simulator.
- **Video previews on an Android device** — `VideoThumbnailInstrumentedTest` over clips made by
  ffmpeg. Install the app and its test APK with `adb install -r`, then run **only that class**:
  `adb shell am instrument -w -e class dev.ehsanfahad.darkchamber.VideoThumbnailInstrumentedTest
  dev.ehsanfahad.darkchamber.test/androidx.test.runner.AndroidJUnitRunner`. Never the Gradle
  `connected…AndroidTest` task on a phone holding a vault you care about: it uninstalls the app, and
  the vault with it.

What remains below needs a device: a real camera, a real photo library, real orientation sensors and a
real screen.

## Build + install

- **Android:** `./gradlew :androidApp:installFossDebug` (device connected, USB debugging on).
- **iOS:** open `iosApp/` in Xcode and run **on a device**. The simulator has no camera; there the
  camera screen can only show that it is not available.

You will need an unlocked vault with at least one album. Screen captures of the app come back blank
because screenshot blocking is on for the whole app — that is itself one of the checks below. A
view-hierarchy dump reads the tree rather than the pixels and is enough for layout, bounds and the
words on screen; the viewfinder's picture can only be judged by eye.

The cautions the other manuals give for driving a device from a terminal apply here too: confirm the
app is in front before every read and every input, and make a failed view dump fail loudly rather than
leave the previous screen's dump in place.

## Checklist

Tick each line. A failure in A, B, E, F, I or J is release-blocking.

### A. Nothing reaches shared storage (property 1) — Android

- [ ] Before shooting, record the number of images in the system media store
      (`adb shell content query --uri content://media/external/images/media --projection _id | wc -l`)
      and list `DCIM`. Take one photo, then a burst of ten. Both are unchanged.
- [ ] After the burst, the vault's scratch directory is empty
      (`adb shell run-as <applicationId> find files/vault/tmp -type f` prints nothing).
- [ ] The vault's object count and thumbnail count each rose by exactly the number of photos taken
      (`run-as … find files/vault/objects -type f | wc -l`, and the same for `thumbs`) — so the capture
      path, not something else, is what ran.
- [ ] No photo in the device's Google Photos, or any gallery app, after the session.

### B. No location, and the right orientation (properties 2 and 3) — Android

Reading what is inside a stored photo needs a build that can see it, and **this app ships no export
yet**. Use a throwaway debug build whose only change is a log line, at the point the bytes are handed
to the import, reporting **whether** the photo carries a GPS block and **which** orientation value it
carries — never the bytes, never a name. Remove it afterwards and reinstall the real build.

- [ ] Every shot reports **no GPS block**.
- [ ] Every shot reports an orientation value, and record **which case the device lands in**: the
      camera's own tag rewritten, no EXIF at all (a block inserted), or EXIF without the tag (left as
      it is — which would show sideways and needs fixing before release).
- [ ] Take one photo holding the phone upright, one turned left, one turned right and one upside down,
      **on each lens**. Once viewers exist, open each: all upright. Until then, compare each logged
      value against the way the phone was held.

### C. The controls

- [ ] Flip switches to the front lens and back; the viewfinder follows. On a device with one lens,
      no flip control is shown and the shutter has not moved.
- [ ] Flash cycles off → auto → on → off, and the mark and its spoken name follow. On a lens with no
      flash, no flash control is shown.
- [ ] Tap the viewfinder: a focus mark appears **under the finger**, including near the edges, and
      the picture refocuses there.
- [ ] Pinch zooms in and out, stopping at the lens's limits.
- [ ] A shot darkens the viewfinder briefly — a cut rather than a fade with reduced motion on — and
      the iris of the shutter blinks.
- [ ] With the ringer on, a shot makes **no shutter sound** on a phone sold where none is required.
      Where the platform reports that one is required (in Japan and Korea, for example), each shot
      plays the system's shutter click.
- [ ] Before the first shot the tile is a plain placeholder; after it, the tile shows that photo. It
      does nothing when touched — no ripple, no highlight, no screen — and a screen reader reads "No
      photo taken yet", then "Last photo saved".

### D. Where photos land (property 7)

- [ ] From the whole vault: the bar reads "All items" with the vault's count. One shot raises it by
      one, and the photo is in no album.
- [ ] From the unfiled items: the bar reads "Unfiled"; the shot raises that count and is unfiled.
- [ ] From an album: the bar reads the album's name; the shot raises **that album's** count, and the
      photo is in the album after closing.
- [ ] A long album name is shortened with an ellipsis and does not push the count off the bar.
- [ ] With the camera open on an album, delete that album from another device or screen if you can.
      The camera says the album is no longer in the vault, that photos already saved are under
      Unfiled, and the shutter is disabled.
- [ ] Closing returns to the listing the camera was opened from, with the vault's bar back.

### E. Release and the lock (properties 5, 6 and 8)

- [ ] On a fresh install, open the camera: the system prompt appears. While it is up the vault stays
      unlocked (on Android the app is paused, not stopped), and allowing it shows the viewfinder
      without leaving the camera.
- [ ] Deny once: the screen explains that the camera is needed and offers **Allow**, which asks again.
      Deny again, with "don't ask again" where the system offers it: the screen says camera access is
      off and offers **Open settings**, which leaves for the system settings — and the vault locks
      while you are there, as the screen said it would.
- [ ] The camera indicator is on while the viewfinder is showing, and goes **off** when you close the
      camera, when you press home, and when the vault locks
      (`adb shell dumpsys media.camera` lists no client for the app).
- [ ] **Lock mid-burst:** take a quick burst and press the power button before it finishes. After
      unlocking, every photo in the vault is whole, none is half-written, the object and row counts
      agree, and scratch is empty. Some of the burst may be missing; none is broken.
- [ ] **Close mid-save:** take a photo and close the camera immediately. The photo is in the listing.
- [ ] Open another app that is using the camera, then open ours: the screen says the camera is in use
      by another app. With the camera unavailable for any other reason it says only that the camera
      isn't available right now — never a cause it was not told.

### F. Nothing leaves as an image (property 4)

- [ ] A screenshot and a screen recording of the camera screen come back **black**, viewfinder and
      tile included. If frames are needed for anything else, that is a separate build with the flag
      removed, and the protected build is reinstalled and shown black again afterwards.
- [ ] The app-switcher card for the app shows no viewfinder and no tile.

### G. The copy (property 9)

- [ ] Read every string on the camera screen and the gallery's camera button: the bar, the count, the
      messages, the explainers and every spoken description. None says silent, never leaves, private
      forever, wiped, erased, secure deletion, or names a cause of failure it was not told. The
      gallery's button is spoken as opening the camera, not as taking a photo.

### H. A device without a camera (property 10)

- [ ] `aapt2 dump badging` on the release APKs lists both camera features as **not required**, and
      the requested permissions are exactly the allowlisted ones — no network state, no location.
- [ ] Where a camera-less device or emulator is available, the app installs, the vault works, and the
      camera screen says the camera isn't available right now.

### I. A video's scratch file (properties 11 and 1) — Android

Watch the scratch directory throughout: `adb shell run-as <applicationId> ls -l files/vault/tmp`.

- [ ] While recording, exactly one file is there, readable by the app alone; within moments of the
      clip being saved it is gone.
- [ ] Lock mid-recording (home, or the power button): the file is gone. Record again, stop, and lock
      while the clip is still saving: the file is gone, and after unlocking the clip is not in the
      vault and nothing half-written is.
- [ ] Kill the app mid-recording (`adb shell am kill <applicationId>` after pressing home, or
      `am force-stop`): the file is still there until the next launch, and gone once the app has
      started again — with no partial item in the vault.
- [ ] Before and after the whole session, count the system's videos
      (`adb shell content query --uri content://media/external/video/media --projection _id | wc -l`)
      and list `DCIM`: unchanged.
- [ ] Nothing about a recording appears in the system log: no path, no file name
      (`adb logcat -d | grep -i -e vault/tmp -e scratch` prints nothing from the app).

### J. How a recording ends (properties 12 and 13) — Android

- [ ] Stop a recording: the clip is in the listing, the count rose by one, the tile reads "Last video
      saved", and the clip has its preview.
- [ ] Press back, and separately ✕, while recording: the clip is kept whole, and the camera indicator
      goes off only after it is (`adb shell dumpsys media.camera`). Back within a fraction of a
      second of starting, too.
- [ ] Stop immediately after starting: either a short clip is kept with its preview, or "Too short to
      save" shows and the scratch directory is empty — never a broken item.
- [ ] Lock mid-recording with photos also still saving, then unlock: one notice names how many photos
      and videos were not saved because the vault locked, shown once.
- [ ] Close the camera while a long clip is saving, then delete the album it was going to from the
      gallery: the clip lands in Unfiled, and a line says so.
- [ ] Record three 4K clips of three to five minutes each on the back lens, and one HD clip: all record,
      save and land, with no crash and no "not responding". Record the bytes per minute, the time to
      encrypt a gigabyte and the app's peak memory during the encrypt.

### K. Space (property 14) — Android

Fill the phone with a throwaway file to change the free space, and remove it afterwards.

- [ ] With just above the floor free, starting a recording is refused: "Not enough space to record."
- [ ] With room for about a minute of HD: the recording stops by itself, the clip is kept, the screen
      says "Recording stopped — not enough space for a longer clip.", and the space-left figure
      reaches zero as it stops.
- [ ] Record the measured reserve and floor this device needs, against the values the app starts
      with.

### L. Sound (property 15) — Android

- [ ] On first switching to video, the system asks for the microphone, and the vault does not lock.
- [ ] Refused: the screen says videos record without sound and offers **Allow**; a clip made now has
      no audio track, and the status line marks it as recording without sound.
- [ ] Turned off with the sound toggle while allowed: the same — no audio track, marked.
- [ ] Granted later in Settings: the next opening of the camera records with sound.
- [ ] Where no shutter sound is required, starting and stopping make no sound of the app's own; where
      one is required, the system's record sounds play.

### M. Shape, rotation and edge to edge (properties 16 and 17) — Android

- [ ] Photo mode: the viewfinder is 4:3; video mode: 16:9 (measure the preview's bounds in a view
      dump). Point at a marked scene and compare its edges in the viewfinder with the saved preview:
      nothing was cropped.
- [ ] Turn the phone through all four ways, on both lenses, in photo and video: the screen does not
      rotate, the icons do, and each capture's preview is upright in the gallery.
- [ ] Open the camera with the phone already sideways: it shows upright at once, without the vault
      locking. Leave it: the rest of the app rotates freely again.
- [ ] In split screen, or on a large screen where the portrait hold is refused, the controls stand
      beside the viewfinder and nothing overlaps.
- [ ] With gesture navigation **and** with three-button navigation: no control sits under the status
      bar or the navigation bar; only the black and the viewfinder run under them.
- [ ] At font scale 1.5 the recording status line and the quality control still fit in portrait.

### N. Video previews (property 18) — Android

- [ ] A recorded clip and a large imported video both get an upright preview. Record how much time
      the preview adds to the large import.
- [ ] Afterwards, nothing new exists in the app's private storage outside the vault's own stores and
      scratch, and nothing in the app's external directories.

### O. iOS — needs a device

None of these can be checked on the simulator, which has no camera. Until a device run has happened,
treat them as **unverified**. Properties 11 to 18 apply to iOS too; check them as in sections I to
N, with Xcode's device file browser in place of `run-as`.

- [ ] Property 1: no new item in Photos after a single shot and a burst.
- [ ] Property 2 and 3: no GPS in a stored shot, and the orientation the system wrote at capture is
      correct at all four ways of holding the phone, on both lenses.
- [ ] The permission alert does not lock the vault, and after a refusal the screen offers only
      **Open settings** — iOS never asks twice.
- [ ] The app-switcher snapshot covers the viewfinder.
- [ ] The camera indicator goes off on close, on background and on lock.
- [ ] A shutter sound plays where the region requires one; no copy calls the camera silent.
- [ ] Flash, focus, zoom and flip behave as in section C; the preview resizes cleanly on rotation.
- [ ] Another app using the camera is reported as in use; other interruptions only as not available.
- [ ] A call or another app interrupting a recording ends it as kept or as reported failed — never a
      screen waiting for ever.
- [ ] The microphone alert does not lock the vault, and the system's own record sounds are what play.
- [ ] The front lens offers the qualities it really records, and its clips are upright.

## Sign-off

Record device models and OS versions, the date, the app version (`:androidApp:printVersion`), which
orientation case the Android device landed in, the JPEG size at the default resolution on each lens,
the app's memory during a burst, the qualities each lens offered, the 4K bitrate and encrypt time from
J, and the reserve and floor from K. Any failure in A, B, E, F, I or J is release-blocking; the rest
are correctness or UX bugs to fix before release.
