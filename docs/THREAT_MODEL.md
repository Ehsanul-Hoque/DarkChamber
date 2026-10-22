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
3. **Casual and forensic inspection of a locked or powered-off device.** Only ciphertext exists on disk: file contents, filenames, metadata, and thumbnails are all encrypted. A full filesystem image without the user's PIN or recovery key yields no plaintext.
4. **Root-level extraction of stored data.** Even with root access to the app's private directory, an attacker obtains ciphertext plus a wrapped master key. Hardware-bound wraps cannot be exported from the secure element; the password wrap requires brute-forcing the passphrase through Argon2id (memory-hard, deliberately slow).
5. **Cloud provider inspection (Phase 3 backup).** Providers store ciphertext with encrypted names and metadata; the backup design is zero-knowledge and we operate no servers.
6. **Shoulder-surfing and incidental leaks.** Screenshot blocking (`FLAG_SECURE`) on Android, app-switcher snapshot obscuring on both platforms, optional tap-to-reveal for sensitive lists.
7. **The platform handing a window's text to another process.** Android builds a structure of the foreground window for its autofill, content-capture and assist services, which run in other processes. Every window the app opens, each dialog included, excludes itself from the **autofill and content-capture** structures, because the text in them is vault content: a name search is a fragment of a file name the user retyped, an album's name is content, and the passphrase is the way to all of it. The **assist** structure is not excluded, and does not need to be for a different reason: the UI toolkit publishes no text into it, so what is built over a window there is layout without content — a property of the toolkit rather than something this app asserts. Measured rather than assumed, on a device — see the out-of-scope note below for what the measurement showed and what it cost.

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

10. **What a photo from the in-app camera carries, and what of it the app can clear.** A shot goes from the camera into memory and from memory into the vault's encryption, with no file on the way — but it is a photo like any other, and two limits follow.
    - **Its metadata.** The app requests no location permission, so no position can be attached; it adds no metadata of its own beyond the orientation tag the photo needs to display upright. What the platform's camera writes — orientation, capture time, the device's make and model — stays in the photo, **inside the encrypted object**, under the same protection as everything else in the vault. As with an imported photo (item 4), it matters when the photo leaves, which is where scrubbing belongs.
    - **Its bytes in memory.** The app zeroes **its own** copies of a shot — after it is saved, after a save fails, and when a lock abandons it. The camera framework's capture buffers, and any copy a decoder makes while generating the thumbnail, belong to the platform and are outside the app's reach; they are freed when the platform frees them, not wiped. No copy may claim that a shot's memory is erased.
    - **Its sound.** On Android the app plays a shutter sound only where the platform reports that the region requires one, and none elsewhere. iOS plays one in some regions whatever an app does; the app cannot mute it, and no copy may call the camera silent.

## Security invariants (enforced in code review)

- Plaintext exists only in memory or in app-private `tmp/`, never in shared storage.
- Any plaintext temp file is deleted immediately after use and on vault lock.
- **A camera photo never touches a file.** It is captured into memory and handed straight to the
  encrypting import; neither platform's save-to-file capture is used, and the import does not stage it
  in `tmp/`. Nothing about a shot reaches shared storage or the system photo library.
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
