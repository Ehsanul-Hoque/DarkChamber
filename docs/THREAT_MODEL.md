# DarkChamber — Threat Model

> This document states plainly what DarkChamber protects against and what it does not. It is a normative part of the project: marketing claims, store listings, and in-app copy must never promise more than this document does. Companion docs: [`ARCHITECTURE.md`](./ARCHITECTURE.md) (how the protections are implemented) and [`FORMAT.md`](./FORMAT.md) (the encrypted container specification).

## Assets we protect

- File contents (photos, videos, audio, PDFs, documents) stored in the vault
- File metadata: original filenames, MIME types, capture data, album names
- Encrypted notes
- Thumbnails and previews
- The vault catalog (what exists, how it's organized)

## In scope — threats we defend against

1. **Other apps or cloud backup services reading vault files.** Vault content lives in app-private storage and never enters shared MediaStore, so scanners (including Google Photos backup) never see it. Content is additionally encrypted at rest.
2. **A device thief or snooping person who does not know the PIN/passphrase.** Master-key material is wrapped by hardware-backed keys (Android Keystore/StrongBox, iOS Secure Enclave) gated on biometric/PIN, with rate-limited unlock attempts. Key material is unrecoverable without successful authentication.
3. **Casual and forensic inspection of a locked or powered-off device.** Only ciphertext exists on disk: file contents, filenames, metadata, and thumbnails are all encrypted. A full filesystem image without the user's PIN or recovery key yields no plaintext. **One exception:** a camera video interrupted by a crash or a forced stop, while it was being recorded or encrypted, leaves its plaintext scratch file in app-private storage until the app is next launched (see the invariants below). Separately, what the app held in memory while the vault was open may have been written to the storage chip by the system's own memory management, which no app controls — see out-of-scope item 13 for what that means and what a user can do.
4. **Root-level extraction of stored data.** Even with root access to the app's private directory, an attacker obtains ciphertext plus a wrapped master key. Hardware-bound wraps cannot be exported from the secure element; the password wrap requires brute-forcing the passphrase through Argon2id (memory-hard, deliberately slow).
5. **Cloud provider inspection (Phase 3 backup).** Providers store ciphertext with encrypted names and metadata; the backup design is zero-knowledge and we operate no servers.
6. **Shoulder-surfing and incidental leaks.** Screenshot blocking (`FLAG_SECURE`) on Android, app-switcher snapshot obscuring on both platforms, optional tap-to-reveal for sensitive lists.
7. **The platform handing a window's text to another process.** Android builds a structure of the foreground window for its autofill, content-capture and assist services, which run in other processes. Every window the app opens, each dialog included, excludes itself from the **autofill and content-capture** structures, because the text in them is vault content: a name search is a fragment of a file name the user retyped, an album's name is content, and the passphrase is the way to all of it. The **assist** structure is not excluded, and does not need to be for a different reason: the UI toolkit publishes no text into it, so what is built over a window there is layout without content — a property of the toolkit rather than something this app asserts. Measured rather than assumed, on a device — see the out-of-scope note below for what the measurement showed and what it cost. Accessibility services are a different channel, given the screen on purpose: out-of-scope item 15.

## Out of scope — threats we do not defend against (and say so)

1. **A compromised or rooted OS with malware running at runtime.** Malware with root/system privileges can read process memory while the vault is unlocked and capture decrypted content in that window. No application on either platform can defend against a hostile OS; we do not claim to.
2. **Coerced unlock.** If a user is forced to authenticate, the vault opens. Duress features (decoy PIN, panic wipe) provide partial, best-effort mitigation only, and we describe them as such.
3. **Recovery of pre-import originals from flash storage.** When a user imports an existing file and deletes the original, flash wear-leveling and garbage collection may retain recoverable copies outside our control. We crypto-shred what we control and disclose this limit at the point of deletion. (See the shredder design in `ARCHITECTURE.md` §10.)
4. **Metadata inside an imported file.** An import encrypts the bytes the user chose, exactly as they are: nothing is parsed, rewritten or stripped on the way in. A photo's EXIF — GPS coordinates, camera serial, capture time — therefore survives inside the encrypted object, which is the honest trade. Rewriting someone's file on import would mean re-encoding it, silently changing what they asked us to keep, and getting it wrong on a format we did not anticipate; and while the item stays in the vault that metadata is encrypted like the rest of it. It matters when the file **leaves**: scrubbing happens on export and in the standalone tool (`ARCHITECTURE.md` §9), which is where the user can see what is being removed. Generating a preview does not change this either — the preview is decoded from the bytes and re-encoded as a new image, so it carries none of the original's metadata.
5. **Screenshot capture on iOS.** iOS does not allow apps to block screenshots; we can only detect and warn. Android blocking via `FLAG_SECURE` is best-effort against OS-level capture.
6. **Traffic analysis of backups (Phase 3).** Upload timing and ciphertext sizes are observable to the network and the storage provider. Padding/obfuscation may be considered later but is not promised.
7. **Weak user secrets.** A 4-digit PIN protected only by Argon2id (i.e., if hardware key protections are somehow bypassed) is brute-forceable. Onboarding nudges users toward 6+ digits or a passphrase; rate limiting and hardware gating are the practical defenses.
8. **The system clipboard, once the recovery key is copied to it.** The recovery-key screen offers a Copy action, because transcribing twenty-six characters by hand is itself a way to lose a vault. The clipboard is a shared surface and we do not control it. What we do set is narrow, and worth stating exactly:
   - **iOS** — the pasteboard write is marked *local only*, so Universal Clipboard does not broadcast the key over iCloud to every device signed into the same account, and carries an *expiration date* (about a minute) that the system honours by itself.
   - **Android** — the clip is flagged *sensitive* (API 33+), so the system shows no preview of it on screen and keyboards that honour the flag keep it out of clipboard history. The flag is a hint the system passes on, not a restriction it enforces: the stock keyboard respects it, another keyboard need not. Android offers no expiry, so the app schedules the clear itself; **if the process dies first, the clip stays**. The clear also leaves the clipboard alone if the user has copied something else since, rather than deleting their work.
   - **Neither platform makes the clipboard private.** Any app the user runs can read it, and on Android before 13 a foreground app can read it silently. The user may also paste the key somewhere that syncs or is backed up. In-app copy therefore says only that the key clears in about a minute and to paste it somewhere safe now — never that copying is secure.

9. **Autofill as a convenience, deliberately given up.** Because the app's windows are excluded from the autofill structures, a password manager cannot fill the passphrase field — there is no way to offer that to one field while keeping vault text out of the structure, since the framework marks every text field in a Compose window as fillable and populates each with its own text. A vault whose passphrase is the only route to its contents is the wrong place to hand that text to another process, so the convenience is the thing that gives way. Typing the passphrase remains the only path besides biometric unlock.

   What the device showed, recorded so the next person does not re-derive it: `FLAG_SECURE` **does not** cover this. With the flag set, focusing a text field still started an autofill session for the activity and sent a fill request to the system's autofill service, carrying one fillable field. After the exclusion, the session still starts — the UI toolkit tells the platform a field was entered and nothing outside it can prevent that — but the structure carries **no fillable field at all**. A dialog is its own window and needed its own exclusion; the host's was not inherited. The assist path is quieter: the toolkit never publishes its text there in the first place, so the structure built over the window is layout without content.

10. **A deleted vault item, from a raw image of the storage — with the passphrase or the recovery key.** Deleting an item destroys its key: the catalog row holding the wrapped key is deleted, its bytes are zeroed in the database files as they are freed, the database's own log is emptied straight after, and the encrypted file is deleted. Without the key the encrypted file is noise, and the app can never open the item again. What it cannot control is the storage chip: as with pre-import originals (item 3), flash wear-levelling may keep old blocks of the database, its journal or its log after the file system has let them go. Someone holding **both** a raw image of the storage **and** the passphrase or the recovery key could in principle find an old copy of a deleted item's wrapped key there, and its ciphertext, and open it. Without the passphrase or the recovery key, the wrapped key opens nothing. Delete copy therefore says the item is removed from the vault for good and that DarkChamber can never open it again — never that it is erased, wiped, or unrecoverable by anyone. (`ARCHITECTURE.md` §10 has the mechanism.)

11. **What a photo from the in-app camera carries, and what of it the app can clear.** A shot goes from the camera into memory and from memory into the vault's encryption, with no file on the way — but it is a photo like any other, and two limits follow.
    - **Its metadata.** The app requests no location permission, so no position can be attached; it adds no metadata of its own beyond the orientation tag the photo needs to display upright. What the platform's camera writes — orientation, capture time, the device's make and model — stays in the photo, **inside the encrypted object**, under the same protection as everything else in the vault. As with an imported photo (item 4), it matters when the photo leaves, which is where scrubbing belongs.
    - **Its bytes in memory.** The app zeroes **its own** copies of a shot — after it is saved, after a save fails, and when a lock abandons it. The camera framework's capture buffers, and any copy a decoder makes while generating the thumbnail, belong to the platform and are outside the app's reach; they are freed when the platform frees them, not wiped. No copy may claim that a shot's memory is erased.
    - **Its sound.** On Android the app plays a shutter sound only where the platform reports that the region requires one, and none elsewhere. iOS plays one in some regions whatever an app does; the app cannot mute it, and no copy may call the camera silent.

12. **What a played video or audio item, a viewed PDF or a viewed text file leaves in memory, and what streaming can check.** A player reads the item a byte range at a time, decrypted in memory; a PDF or a text file is read whole into memory. The app writes no file of any of them. Four limits follow.
    - **Its bytes in memory.** The app's own reader holds at most two decrypted chunks and zeroes each as it lets it go. Everything past that point belongs to the platform: the player's read-ahead (capped at about fifteen seconds on Android and twelve on iOS, because all of it is plaintext), the decoders' input and frame buffers, and the audio and video pipelines below them. They are released when the player is — on leaving the page, closing the viewer, or a lock, which does not wait for it — and freed when the platform frees them, **not wiped**. No copy may claim that a played item's memory is erased. Whether AVFoundation keeps anything of a stream beyond its memory buffers has not yet been checked on an iPhone; the app hands it bytes only in memory.
    - **What is verified as it plays.** Every chunk is authenticated before any of it reaches the player: a changed, reordered, truncated or spliced chunk fails its tag, and playback stops there with the page saying the file could not be verified. What streaming **cannot** check is the digest of the whole plaintext, which needs every byte read first (`FORMAT.md` §2.3): the parts played before a damaged chunk were each authentic, and a damaged chunk the player never reaches — past where it was stopped, or skipped over by a seek — goes unnoticed while playing. Against anyone without the item's key the per-chunk tags already decide; the digest is a defence against the app's own chunking going wrong.
    - **A viewed PDF's bytes in memory.** A PDF is decrypted whole into one buffer the platform's renderer reads — on Android an anonymous memory-only file that no path names, on iOS an in-memory data object — and opened only once the whole file, digest included, has verified. While its page is shown, **all of its plaintext is in memory**, together with the renderer's own parse of it and the pages drawn near the screen. Leaving the page, closing the viewer or a lock releases them; a lock that finds a page being drawn waits for that page to finish, because a renderer cannot be interrupted, so the plaintext stays in memory for as long as one page takes to draw. The app zeroes its own transfer buffer, and on iOS the buffer it filled; Android's memory file and both platforms' renderers are freed when released, **not wiped**. No copy may claim that a viewed PDF's memory is erased.
    - **A viewed text file in memory.** A text file — plain, code, JSON, Markdown, or HTML shown as its source — is decrypted whole into one array of its stored size, up to 8 MiB, and decoded only once the whole file, digest included, has verified. The app zeroes that array as soon as it is decoded, and on every failure. The decoded text cannot be zeroed: the platforms' strings are immutable, so the text, any re-indented or formatted form of it, the widths of the characters it holds (measured to estimate how tall its rows are), and the rows laid out on screen stay in memory while the page is shown and are released — **not wiped** — when the page is left, the viewer closes or the vault locks. While a text page is shown, **all of its text is in memory**, often more than once. No copy may claim that viewed text is erased from memory.

13. **Memory the system writes to storage (swap).** Phones stretch their memory by compressing it and, on many, by writing part of it to the storage chip when memory runs short. An app cannot opt out: Android lets an app hold only a few dozen kilobytes in memory that the system will never move. So whatever the app holds while the vault is open — decoded pictures and previews, playback buffers, an open PDF, a text file being read, and the vault's keys themselves — may be written to the storage chip by the system, outside the app's control and without its knowledge. The app writes no plaintext file of its own beyond the ones the invariants below name; it cannot promise that nothing of an open vault ever reaches storage, and no copy may. What a user can do, worded at what has been checked:
    - **Restart the phone after using the vault.** A restart empties memory. On stock Android, the place the system writes memory to on the storage chip is encrypted with a key made at each boot and discarded at shutdown, so after a restart what was written there can no longer be read. Whether a manufacturer's own memory-extension feature writes to that same protected place has not been verified.
    - **Turn off a "RAM extension" or "memory extension" setting**, where the phone has one (the name varies by manufacturer). This is the feature that writes memory to storage on many phones; whether turning it off stops every such write has not been verified on every phone.
    - **Lock the vault when done with it.** A lock releases what the app held — freed, not wiped — so the less time the vault is open, the less of it is in memory for the system to write out.

14. **A web link opened from a file in the vault.** A Markdown file can hold links, and a reader may choose to open one. The address is content of the file, and opening it hands that address to another app — the browser — which this app does not control. Only `http` and `https` addresses can be opened, only after a dialog the reader confirms, and never by tapping the link alone; every other kind of link (`javascript:`, `intent:`, `file:`, `mailto:` and the rest) does nothing. What follows from the reader's Open:
    - **The address leaves the vault.** The browser receives it exactly as the file writes it, and may keep it in its history, suggest it later, and sync it to the reader's other devices and the browser maker's servers. The browser fetches the page over the network; DarkChamber itself sends nothing — it gains no permission and no network code, and no part of the file other than that one address is handed over.
    - **The site can learn it was opened.** A server sees the request, and an address can be built to tell who was sent it — a tracking link in a shared document reports when, and from what network, it was opened. The dialog says the site can learn this; the app cannot tell a tracking address from any other.
    - **Look-alike addresses are not detected.** The dialog shows the full address as written, with the site it reaches on its own line, so a link whose words name one site and whose address names another is visible, as is an address with a user part before the site (`https://bank.example@other.example/` reaches `other.example`). Addresses holding invisible or direction-changing characters are not opened at all, nor are addresses holding a backslash, which browsers and iOS read differently, so the site line could not name the site truly. But letters from other scripts that look like Latin ones (a homograph domain), and sites with similar names, are shown as written and not flagged: telling the real site from an imitation is left to the reader and the browser.
    - **The vault locks once the address is handed over.** The app locks itself as soon as the platform reports that an app opened the address, because on a phone in split-screen or a floating window the browser can open beside the vault without backgrounding it. If no app opens it, nothing is locked and the app says the link was not opened.

15. **Accessibility services the user turned on.** A screen reader or any other accessibility service the user has enabled is given what the app shows, so that it can read it aloud or act on it: file and album names in the gallery, the lines of a text page, a link's address in the confirmation, every label and message. That is the point of such a service, and the app supports screen readers on purpose; it cannot hand them the screen while keeping the vault's words from them. An accessibility service is another app with the user's permission, outside this app's control: what it does with what it is given — keep it, send it anywhere — is up to that app. This is separate from the autofill and content-capture structures (in-scope item 7), which the app does keep vault text out of. Turn on only accessibility services you trust, and know that while one is on, it sees what you see in the vault.

## Security invariants (enforced in code review)

- Plaintext exists only in memory or in app-private `tmp/`, never in shared storage. This governs what
  the app writes; memory the system itself writes to storage is out-of-scope item 13.
- Any plaintext temp file is deleted immediately after use and on vault lock.
- **A camera photo never touches a file.** It is captured into memory and handed straight to the
  encrypting import; neither platform's save-to-file capture is used, and the import does not stage it
  in `tmp/`. Nothing about a shot reaches shared storage or the system photo library.
- **A camera video passes through one plaintext file in app-private `tmp/`** while it is recorded
  and encrypted — the one place this app's own capture writes plaintext to disk. The file has a
  random name, there is never more than one, and it is deleted after the encrypt, on every failure,
  on lock, and on the next launch after a crash. Deleted flash blocks may outlive the delete
  (wear-levelling), as for the shredder: the file's contents can survive on the storage chip after it
  is gone from the file system, out of reach of any app but not of forensic recovery from the chip.
  Nothing about a clip reaches shared storage or the system photo library.
- **A video or audio item plays from memory only.** The player reads it through the vault's range
  reader; there is no player cache, no file and no `tmp/` stage. Nothing about it reaches a media
  session, a notification, the lock screen or an external playback route, and the player asks for no
  permission the app did not already hold.
- **A PDF is shown from memory only.** It is decrypted into a buffer no path names — an anonymous memory
  file on Android, in-memory data on iOS — and handed to the platform's renderer only after the whole
  file has verified; there is no file, no `tmp/` stage and no cache of the document or its drawn pages.
  Where the platform can render only from a file (Android before 11), PDFs are not shown at all.
- **A text file is shown from memory only, and never through a web engine.** It is decrypted into an
  array, decoded only after the whole file has verified, and drawn by the app's own code; no web view
  and no platform HTML parser ever receives vault content, and nothing on a text page can be selected
  or copied. A link in it opens only after the reader confirms a dialog showing the full address, only
  for `http`/`https`, only through the one link-opening seam, and the vault locks once the address has
  been handed over (out-of-scope item 14).
- No plaintext thumbnails outside the encrypted thumbnail store. A preview is generated in memory,
  sealed under its own key and written only there; no image cache on disk, at any layer, ever holds
  what it decrypted.
- **Decrypted previews do not survive a lock.** A gallery keeps decrypted images in memory so that
  scrolling does not decrypt the same tile repeatedly, and that cache is vault content like any
  other: it is emptied as part of the same teardown that zeroizes the MasterKey and purges the
  scratch directory. A locked app that still holds pictures of the user's files looks exactly like a
  locked app, so nothing but this invariant would catch it.
- Raw MK/DEKs are zeroized best-effort after use (GC-copy limits acknowledged).
- The vault locks when the app goes to the background. **One exemption:** a file picker the app
  launched itself is a separate activity on Android, so reaching it backgrounds us — locking there
  would destroy the screen the picker's result returns to, making import impossible. While such a
  handoff is outstanding the lock is *deferred*, never skipped: the MasterKey stays in memory for
  the length of the pick, a watchdog locks the vault if the picker is abandoned (2 minutes), and the
  app-switcher card stays blank throughout. Nothing else — the home button, another app, the screen
  locking — is exempt.
- **A lock always wins over work in flight.** An import runs for as long as its files take, and it
  belongs to the unlocked session rather than to a screen, so leaving the gallery does not abandon
  it. Locking still ends it: the session's work is cancelled *before* the MasterKey is zeroized, the
  encrypt notices within a buffered read, and the interrupted file leaves no object, no catalog row
  and no scratch. Nothing about a running import is persisted across a lock, which is why the app
  warns before an import rather than explaining afterwards.
- **Every window that shows vault content is excluded from the platform's autofill and
  content-capture structures.** A dialog is its own window and does not inherit the exclusion set on
  the host's, so each one asks for it itself; an architecture rule fails the build when a dialog is
  opened without it, because the next dialog to hold a text field is the one that would forget.
- A violation of any invariant is a P0 security bug, regardless of user impact observed.

## Reporting

Vulnerabilities: see [`SECURITY.md`](../SECURITY.md) at the repository root for the disclosure process and timelines.
