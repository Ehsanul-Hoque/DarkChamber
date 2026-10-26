# DarkChamber — Project Roadmap

> *Encrypted vault & private camera. A camera obscura for the modern age: what enters the chamber never leaves it.*

> **Status:** In development (Phase 1) · **Platforms:** Android & iOS · **Stack:** Kotlin Multiplatform + Compose Multiplatform
> **License:** GPLv3 (app), trademark reserved · **Model:** Free & open source, one-time purchase for cloud sync

---

## 1. Vision

A cross-platform, open-source app that lets anyone encrypt, store, and view private files — photos, videos, audio, PDFs, notes, and arbitrary documents — with modern, audited cryptography and zero data collection. Files captured with the built-in private camera never touch shared storage, so cloud services like Google Photos never see them.

**Positioning statement:** *The open-source alternative to Keepsafe — local-first, one-time purchase, no ads, no trackers, verifiable crypto.*

### Guiding principles

1. **Plaintext never touches shared storage.** Decrypted data exists only in memory or app-private storage, and only for as long as needed.
2. **Zero knowledge by design.** We never see user files or keys. Cloud backup (Phase 3) encrypts client-side and uploads to storage the *user* controls.
3. **Honesty over marketing.** We do not promise what flash storage cannot deliver (e.g., guaranteed erasure of imported originals). Crypto-shredding, clearly explained, over snake-oil "military-grade wiping."
4. **The free tier is a complete product.** No crippled basics. Paid unlock covers features with ongoing value/cost (cloud sync), never core security.
5. **Trust is the product.** Open source, reproducible builds, F-Droid presence, eventual third-party audit.

### Explicit non-goals

- ❌ **No background auto-encryption of photos taken by other apps.** Infeasible on iOS, unreliable and Play-policy-hostile on Android (`MANAGE_EXTERNAL_STORAGE`). The private in-app camera is our answer.
- ❌ **No app-disguise / fake calculator icons.** Conflicts with Google Play Misrepresentation policy and our trust-first brand.
- ❌ **No ads, no analytics SDKs, no trackers.** Ever. (Opt-in, self-hosted crash reporting at most.)
- ❌ **No custom cryptographic primitives.** Standard algorithms via vetted libraries and OS hardware only.
- ❌ **No app-lock feature.** Requires the Accessibility/Usage-Access/overlay permission trio that contradicts our minimal-permission brand; OS vendors now ship this natively. See ADR-002.
- ❌ **No app sandboxing / isolation of other apps.** Cross-app tracking is overwhelmingly server-side and SDK-side; a sandbox can't stop it and promising otherwise would be dishonest. See ADR-003.

---

## 2. Monetization & Licensing

| Component                                                               | Model                                                                                                                                           |
|-------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| All local features (vault, camera, viewers, notes, scrubber, shredder)  | **Free forever**, on every store and F-Droid                                                                                                    |
| Encrypted cloud backup/sync to user's own Drive/iCloud (Phase 3)        | **One-time purchase** (~$10–15) on Play/App Store                                                                                               |
| Optional hosted zero-knowledge storage (Phase 5, only if demand exists) | Subscription priced against real storage costs                                                                                                  |
| Donations                                                               | Accepted (GitHub Sponsors / Liberapay / OpenCollective) — treated as a bonus, never the plan                                                    |
| License                                                                 | **GPLv3** for all app code; server code (if any) AGPLv3                                                                                         |
| Trademark                                                               | App name + logo excluded from the open-source license; separate trademark policy (Signal/Bitwarden pattern) to keep clones from using our brand |

F-Droid builds include everything including sync (honor system, like Conversations). Store builds gate sync behind the one-time IAP. We expect and accept that technical users can self-build; the store price is convenience + support.

---

## 3. Release Phases

### Phase 0 — Foundation

Goal: a walking skeleton with the security core proven before any product UI.

> **Progress (2026-09): Phase 0 is complete.** The encrypted-vault core is built and verified:
>
> - `:core:crypto` — the `.dchm` codec + key hierarchy (Argon2id/PBKDF2/recovery, `VaultSession`) +
>   the hardware `SecureKeyStore` / biometric seams, with committed test vectors.
> - `:core:storage` — `VaultStorage` (atomic object/thumbnail/key-blob stores + `tmp/`), no crypto dep.
> - `:core:db` — SQLDelight catalog (opaque encrypted columns) behind a fail-closed `CatalogStore`.
> - `:core:vault` — `VaultObjects`: streaming import/open/openMeta/shred (composes the three cores).
> - `:app` — the composition root: Koin graph, `VaultBootstrap` cold-open, the Decompose session gate,
>   cryptographic per-use biometric unlock, and the lock lifecycle — on an Android `FragmentActivity`
>   and an iOS `ComposeUIViewController`.
>
> The exit criteria below are **met** (streaming encrypt + 2 GB stream-decrypt in bounded RAM;
> kill → relaunch → biometric unlock, plaintext never on disk). **Phase 1 picked up from here** — the
> camera/import + viewer UI; its progress note is below. See the git history and `docs/` for detail.

- KMP project scaffold: `core-crypto`, `core-storage`, `core-db`, `app` (Compose Multiplatform), CI on GitHub Actions (Android + iOS builds, unit tests).
- **Crypto core v1:** AES-256-GCM chunked streaming encryption/decryption (the `.dchm` container format — see docs/FORMAT.md), envelope key hierarchy, Argon2id KDF.
- Platform keystores wired behind the injected `SecureKeyStore` seam: Android Keystore (StrongBox where available), iOS Keychain — device-unlocked now, per-use biometric with a later phase.
- Vault metadata DB (SQLDelight) with encrypted fields; encrypted thumbnail store.
- Test vectors + property tests for the container format; round-trip fuzzing.

**Exit criteria:** encrypt → kill app → relaunch → biometric unlock → decrypt a 2 GB video by streaming, on both platforms, with plaintext never written to disk. ✅ *(the streaming encrypt/decrypt capability landed in `:core:vault`: bounded-RAM import + stream-decrypt, host/sim-tested at a configurable size with a 2 GiB opt-in + an on-device checklist; kill→relaunch→unlock proven earlier in Phase 0.)*

### Phase 1 — MVP: Vault + Private Camera → **v1.0**

The complete free product. Everything here ships open source and free.

> **Progress (2026-10): Phase 1 is under way — the vault half of it is in place, and the camera takes
> photos and records video; the viewers are next.**
> What a build can do today, on Android and iOS — the readme describes each of these for a reader
> rather than a planner:
>
> - **Hold files.** An adaptive grid over the encrypted catalog, behind the Phase 0 session gate, with
>   batch import from the file and photo pickers — bounded RAM, honest two-level progress, continue
>   past an unreadable file, and a stop that lands **within** a file rather than after it.
> - **Show what is in there.** Previews generated at import, each sealed under its own key, decoded to
>   memory only. A file with no preview shows its type; a preview is a convenience, never evidence.
> - **Offer to delete the original**, where the system allows it, behind a disclosure that says what
>   deletion on flash storage can and cannot promise.
> - **Organise into albums** — created, renamed, deleted, filled by moving a selection in, and the
>   destination of an import made from inside one. Two things the words suggest and the app does not:
>   **deleting an album keeps every file in it** (they become unfiled, and the confirmation says so),
>   and an item is in **one album at a time**, so filing moves it. An album organises; it is not a
>   second lock.
> - **Find one file** among thousands: four orders, a search over names, and a line in the bar saying
>   which listing you are looking at. Both run on rows already on screen, because a name lives inside
>   the encrypted metadata and no query can sort or match what it cannot open — so re-ordering and
>   typing never re-open a row. An empty grid names which of five reasons it is empty.
> - **Survive the cloud.** A file that has to be downloaded before it can be encrypted says it is
>   waiting, and **stopping actually stops** — the provider's transfer carries on, because nothing can
>   call it off, and whatever it hands over is thrown away unread.
> - **Take a photo privately.** A camera opened from any listing files each shot where an import from
>   there would, and its count moves only once the shot is in. The photo goes from the camera into
>   memory and from memory into the vault's encryption — no file on the way, nothing in the system
>   photo library, no location requested. Only the app's own copies are cleared afterwards; the
>   camera framework's buffers are the platform's. Where a region requires a shutter sound, it plays.
> - **Record video privately**, in HD or 4K where the lens can, with sound optional. A clip is too
>   large to hold in memory, so it passes through **one plaintext file in the app's private storage**
>   while it is recorded and encrypted, and that file is deleted on every way out — saved, failed, too
>   short, or abandoned by a lock; a crash leaves it until the app next starts. Deleted is not wiped:
>   flash storage can keep the old blocks, as the threat model says of the shredder. Recording is limited
>   to what the phone has room to encrypt, and a full disk is named only when the phone reports one.
>   Anything a lock or a failure discarded is counted, never described, on the next unlocked screen.
> - **Give nothing to the rest of the phone.** Screenshot and recording blocking plus a blank
>   app-switcher card on Android, an obscured snapshot on iOS (which offers no way to block a
>   screenshot at all — the threat model says so and no copy here softens it), and every window kept
>   out of the platform's autofill and content-capture structures. That second channel is **not**
>   covered by the screenshot flag: a device showed a vault text field travelling it with the flag set.
>   The cost is stated rather than hidden — a password manager cannot fill the passphrase.
>
> Still to come before v1.0: the in-app viewers and the seekable reads they need,
> export with metadata scrubbing, the settings screen and recovery-key entry, and the
> release-readiness tooling.
>
> **Verified on Android hardware; iOS only on the simulator.** The platform-specific paths there —
> the pickers, the preview encoder, the camera — have not been run on a device, which is a
> pre-release gate rather than something to imply away. What automated tests cannot show at all has
> its own checklists in [`docs/verification/`](./docs/verification), one per area, with the
> release-blocking lines marked: that a name never reaches the catalog file or a log line, that a
> layout holds at a large font scale, that no window hands its text to another process, that a
> photo never reaches shared storage or carries a location, and that a video's scratch file is gone
> however a recording ends — after a crash, at the next launch.

| Feature                                | Notes                                                                                                                                                                                               |
|----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Encrypted vault with albums             | Import from gallery & file picker; batch import; albums as labels (one per item, deleting an album keeps its files); sort/search on encrypted metadata                                               |
| **Private camera** (photo + video)     | CameraX / AVFoundation → encrypting import → app-private storage. A photo never touches a file; a video passes through one app-private scratch file while it is recorded and encrypted, then is deleted |
| In-app viewers                         | Images (in-memory decode), video & audio (streaming decrypt via ExoPlayer custom DataSource / AVAssetResourceLoaderDelegate), PDF (PDFKit in-memory on iOS; private-cache temp or memfd on Android) |
| "Decrypt & open in…" fallback          | Share-sheet export for docx/zip/other, with explicit "file is leaving the vault" warning + cleanup action                                                                                           |
| Unlock: PIN + biometrics               | Hardware-backed; rate-limited PIN attempts                                                                                                                                                          |
| **Recovery key** flow                  | Forced at setup; brutal clarity that loss = permanent data loss                                                                                                                                     |
| Screenshot & app-switcher protection   | `FLAG_SECURE` on Android; obscured snapshot on iOS                                                                                                                                                  |
| **Import-time honesty dialog**         | When deleting an original after import, explain flash-storage recoverability limits                                                                                                                 |
| Basic **metadata scrubbing on export** | Strip EXIF/GPS when a file is exported/shared from the vault (default on)                                                                                                                           |

**Exit criteria:** crash-free 4K video capture-to-vault; smooth scrubbing/seeking of encrypted video; recovery-key restore verified on fresh installs; Play + App Store approval (export compliance declared).

### Phase 2 — Requested Utilities & Security Hardening → **v1.x**

| Feature                                      | Notes                                                                                                                                                                                                                                                                           |
|----------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **File metadata scrubber** (standalone tool) | Inspect + strip EXIF/XMP/IPTC/GPS from images (and mp4 metadata atoms) *without* importing to the vault. "Clean copy" output; before/after metadata diff view. Doubles as a funnel feature                                                                                      |
| **Encrypted notes**                          | Markdown notes stored as first-class vault items (same container format, `text/markdown`). Editor with autosave to encrypted store only                                                                                                                                         |
| **Secure file shredder**                     | Two honest modes: (a) **crypto-shred** vault items (destroy DEK — instant, genuinely irreversible); (b) best-effort overwrite+delete for external files, with an unavoidable explanation that flash wear-leveling means recovery may still be possible. No "DoD 7-pass" theater |
| Decoy / duress PIN                           | Opens a separate, plausible fake vault                                                                                                                                                                                                                                          |
| Intruder selfie + break-in log               | After N failed unlocks (front camera, stored encrypted)                                                                                                                                                                                                                         |
| Per-album passwords                          | Independent KEKs per album. **Not built in v1**, and the album surface says nothing that implies it is: today every album's name is sealed under the master key like item metadata, and an album is organisation rather than a boundary. The catalog records which variant wrapped an album so that this can arrive without a migration |
| Panic wipe                                   | Long-press gesture / duress PIN variant → crypto-shred everything                                                                                                                                                                                                               |
| Localization + accessibility pass            | TalkBack/VoiceOver coverage                                                                                                                                                                                                                                                     |

### Phase 3 — Encrypted Cloud Backup → **v2.0** 💰

The one-time-purchase unlock. Client-side-encrypted backup/sync of the vault to **the user's own** Google Drive (Android+iOS) and iCloud Drive (iOS), using their account. We run no servers and hold no keys.

- Ciphertext-only upload: file contents, filenames, and metadata all encrypted before leaving the device.
- Resumable, chunk-level upload/download (the exact large-video pain point Keepsafe users complain about); delta sync; bandwidth/Wi-Fi-only controls.
- Multi-device restore via recovery key.
- Purchase: single IAP unlock; F-Droid build ships it enabled.

**Exit criteria:** interrupted 5 GB video upload resumes correctly; full-vault restore on a new device via recovery key alone.

### Phase 4 — Trust Milestones (parallel from Phase 2)

- **Reproducible builds** + published APK signatures; F-Droid inclusion.
- **Third-party audit** of `core-crypto` and the container format (scope-limited to keep cost sane, e.g. Cure53 / Radically Open Security). Publish the report, fix everything, publish the fixes.
- Public threat model + security policy + `SECURITY.md` with a disclosure process.
- Community: Privacy Guides / r/privacy engagement, transparent changelogs.

### Phase 5 — The Suite (post-v2, separate apps sharing the KMP core)

Separate, focused apps — not a permission-hungry mega-toolbox:

1. **TOTP/2FA authenticator** — encrypted export/import, optional encrypted sync via the same Phase-3 engine. (Authy-desktop refugees are still looking for a home.)
2. **Standalone metadata scrubber** — the Phase-2 tool as its own tiny app for reach.
3. **Encrypted notes** as a standalone app, if vault-notes usage justifies it.
4. Evaluate later, high bar: password manager (only with audit budget). **Avoid:** VPN, browser, encrypted messaging.

---

## 4. Milestone Summary

| Milestone     | Target                                               | Paid?        |
|---------------|------------------------------------------------------|--------------|
| v0.x internal | Crypto core proven on both platforms                 | —            |
| **v1.0**      | Vault + private camera + viewers + recovery          | Free         |
| v1.x          | Scrubber, notes, shredder, decoy vault, intruder log | Free         |
| **v2.0**      | Encrypted backup to user's own cloud                 | One-time IAP |
| v2.x          | Audit published, reproducible builds, F-Droid        | —            |
| Suite         | Authenticator app on shared core                     | Free / TBD   |

## 5. Success Metrics

- Crash-free sessions > 99.5%; zero plaintext-on-shared-storage defects (treated as P0 security bugs).
- v1: 10k installs via privacy-community organic reach; F-Droid listing live.
- v2: IAP conversion ≥ 2–3% of active users (typical for utility one-time purchases); support load dominated by questions, not data-loss reports.
- Audit published with all criticals/highs resolved.

## 6. Top Risks

| Risk                                                                 | Mitigation                                                                                           |
|----------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| Recovery-key loss → user data loss → 1-star reviews                  | Forced key backup at onboarding, verification step, repeated warnings, optional printed-sheet export |
| Young KMP libraries (`cryptography-kotlin` pre-1.0, camera wrappers) | Pin versions, wrap behind our own interfaces, budget to fork/maintain                                |
| Play/App Store review friction (encryption export, background media) | Declare export compliance early; no restricted permissions; keep a policy checklist per release      |
| Vault-app clones on Play                                             | GPLv3 + trademark enforcement; brand kept out of the code license                                    |
| Scope creep toward mega-toolbox                                      | Non-goals list above is binding; suite = separate apps                                               |
