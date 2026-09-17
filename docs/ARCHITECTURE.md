# DarkChamber — Architecture

> How DarkChamber is built: modules, crypto integration, storage, and per-feature design. Companion docs: [`THREAT_MODEL.md`](./THREAT_MODEL.md) (what we defend against — normative for all product claims), [`FORMAT.md`](./FORMAT.md) (the `.dchm` container specification — normative for all implementations), and [`DESIGN.md`](./DESIGN.md) (the visual language — tokens, themes, motion). Roadmap: [`../ROADMAP.md`](../ROADMAP.md). Decisions: [`ADR/`](./ADR/).

Language: Kotlin Multiplatform. Shared UI: Compose Multiplatform.

---

## 1. Overview

```
┌────────────────────────────────────────────────────────────┐
│                  Compose Multiplatform UI                  │
│        (screens, navigation, viewers, camera UI)           │
├────────────────────────────────────────────────────────────┤
│                     feature modules                        │
│  vault · camera · viewers · notes · scrubber · shredder    │
│                 · backup (Phase 3) ·                       │
├────────────────────────────────────────────────────────────┤
│                     shared core (KMP)                      │
│  core-crypto   core-storage   core-db   core-model         │
├────────────────────────────────────────────────────────────┤
│              platform layer (expect/actual)                │
│  Keystore/StrongBox ↔ Secure Enclave/Keychain              │
│  CameraX ↔ AVFoundation      Media3 ↔ AVPlayer             │
│  BiometricPrompt ↔ LocalAuthentication                     │
│  FLAG_SECURE ↔ snapshot obscuring                          │
└────────────────────────────────────────────────────────────┘
```

### Module layout

| Module              | Contents                                                                                                                      | Platform code?                                                                           |
|---------------------|-------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| `core-model`        | Domain types: `VaultItem`, `Album`, `Note`, MIME handling                                                                     | No                                                                                       |
| `core-crypto`       | `.dchm` codec (per [`FORMAT.md`](./FORMAT.md)), key hierarchy, KDF, DEK lifecycle                                             | KDF + cipher via `cryptography-kotlin`; key *storage* via expect/actual `SecureKeyStore` |
| `core-storage`      | App-private file layout, atomic writes, temp-file hygiene, thumbnail store                                                    | Small expect/actual for paths                                                            |
| `core-db`           | SQLDelight schema + DAOs for the encrypted catalog                                                                            | Driver per platform                                                                      |
| `core-designsystem` | Theme + tokens per [`DESIGN.md`](./DESIGN.md), shared components, secure-screen wrappers (`FLAG_SECURE` / snapshot obscuring) | Small expect/actual for the secure-screen wrapper                                        |
| `feature-*`         | ViewModels + Compose screens per feature                                                                                      | Viewers/camera have expect/actual internals                                              |
| `app`               | Compose Multiplatform entry points, DI (Koin), navigation                                                                     | Thin                                                                                     |

### Key dependencies

| Concern           | Library                                                                                                      | Notes                                                                                                                                           |
|-------------------|--------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| Crypto primitives | `dev.whyoleg.cryptography-kotlin`                                                                            | Wraps JCA / CryptoKit / OpenSSL behind one KMP API. Pre-1.0 → pin exact version, wrap behind our own `CryptoEngine` interface so it's swappable |
| Argon2id          | Platform bindings (e.g. libsodium via cinterop / signal-argon2 on Android) behind `expect fun argon2id(...)` | PBKDF2-HMAC-SHA256 (≥ 600k iters) as tested fallback                                                                                            |
| DB                | SQLDelight                                                                                                   | Sensitive columns encrypted at the application layer (§5); consider SQLCipher later as defense-in-depth                                         |
| Serialization     | kotlinx.serialization                                                                                        | Header + metadata blobs                                                                                                                         |
| DI                | Koin                                                                                                         | KMP-friendly                                                                                                                                    |
| Camera            | CameraX / AVFoundation (direct, or CameraK wrapper — vet & pin)                                              | See §6                                                                                                                                          |
| Media playback    | Media3/ExoPlayer · AVPlayer                                                                                  | Custom decrypting data sources, §7                                                                                                              |
| Images            | Coil 3 (KMP) with custom encrypted `Fetcher`                                                                 | Decodes from in-memory decrypted bytes                                                                                                          |

**Rule:** all young/single-maintainer libraries sit behind interfaces we own. If upstream dies, we fork or replace without touching feature code.

## 2. Threat model

Moved to [`THREAT_MODEL.md`](./THREAT_MODEL.md). Its "security invariants" section is enforced in code review; a violation is a P0 bug.

## 3. Cryptography integration

The key hierarchy, container format, KDF parameters, and cipher registry are specified normatively in [`FORMAT.md`](./FORMAT.md). Summary for orientation: a random 256-bit MasterKey (MK) is wrapped three ways — by a hardware-keystore key, by an Argon2id-derived key from the user's passphrase, and by the recovery key. MK wraps per-object DEKs; DEKs encrypt content as chunked AES-256-GCM `.dchm` containers. Password change re-wraps MK only; destroying a DEK crypto-shreds one item; destroying MK wraps crypto-shreds the vault.

What lives *here* rather than in the format spec is the platform integration:

### 3.1 Platform key storage (`expect/actual SecureKeyStore`)

|                                        | Android                                                                                                 | iOS                                                                                                                      |
|----------------------------------------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| HW wrap key                            | Keystore AES key, `setUnlockedDeviceRequired`, StrongBox if present, auth-required with validity window | Secure Enclave P-256 → ECIES-wrapped MK, or Keychain key with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` + LAContext |
| Biometric gate                         | `BiometricPrompt` (BIOMETRIC_STRONG or device credential)                                               | `LocalAuthentication` (Face ID/Touch ID, passcode fallback)                                                              |
| Invalidated by biometric re-enrollment | Optional (off by default; PIN path remains)                                                             | Optional via `accessControl` flags                                                                                       |

Failed-attempt policy enforced in app logic: exponential backoff after 5 failures; after N (user-configurable) failures optionally trigger intruder selfie (§10) and/or panic crypto-shred.

### 3.2 Key material hygiene

Raw MK/DEKs exist only in memory, held in a single `VaultSession`, zeroized best-effort on lock (`ByteArray.fill(0)`; GC-copy limits acknowledged in the threat model).

### 3.3 Vault object pipeline (`core:vault`)

**Status — built.** `core:crypto`, `core:storage`, and `core:db` are isolated from each other by design (no cross-dependency); `core:vault` is the one place they compose. `VaultObjects` exposes the streaming object operations that close the Phase-0 exit criteria:

- **`importObject`** — mint a per-object DEK (`VaultSession.generateDek`, wrapped under MK), stream-encrypt the source into a `.dchm` object via `DchmCodec.encryptContainer` written atomically to `VaultStorage` (bounded RAM — the codec's two-pass read needs a re-openable source, which captured/imported media on disk provides), seal a minimal `enc_meta` ({name,mime}) under MK (`VaultSession.sealMeta`, a distinct AAD from DEK-wrap), and persist the `items` row carrying the wrapped DEK + the measured plaintext size. **Fail-closed:** a row-insert failure deletes the just-written object (no orphan); a codec error aborts the atomic write and surfaces the precise `VaultError`.
- **`openObject`** — read the row, unwrap the DEK (`useDek`, zeroized after), and stream-decrypt the container to a caller sink in bounded RAM. The `nonce_prefix` comes from the container prelude, not the row.
- **`openMeta`** — decode {name,mime} from the row's `enc_meta` without touching the object store (cheap for a gallery listing).
- **`shredObject`** — crypto-shred: delete the row (destroying `dek_wrapped`) **first**, then best-effort delete the object bytes; orphaned ciphertext without a DEK is unrecoverable, so a failed byte-delete does not un-shred.

Everything returns `Outcome` (no throw crosses), and `core:vault` imports **no cipher primitive** — `.dchm` bytes stay opaque, crypto stays behind `DchmCodec`/`VaultSession`. The bounded-RAM property is proven by a generating-source streaming test at a configurable size (`dchm.stream.bytes`; 2 GiB opt-in) plus an on-device checklist. **Not built here:** random-access seek reads (§4) and the media viewers (§7) — those are Phase 1; `openObject` streams sequentially.

## 4. Storage layout (app-private only)

```
filesDir/                         (Android: /data/data/<pkg>/files · iOS: app container/Documents, excluded from iCloud backup via URLResourceValues)
 ├─ vault/
 │   ├─ objects/ab/cd/<file_id>.dchm        # content-addressed-ish sharding
 │   ├─ thumbs/<file_id>.tdchm              # encrypted thumbnails (~256px)
 │   └─ keys/mk.wrapped                     # MK wraps (hw / pw / recovery)
 ├─ db/catalog.db                           # SQLDelight (fields encrypted, §5)
 └─ tmp/                                    # decrypt scratch (PDF path), wiped on lock & on launch
```

Rules: writes are atomic (temp + rename); `tmp/` is purged on vault lock, app start, and after each viewer session; **nothing is ever written to shared storage** except explicit user exports; Android `android:allowBackup="false"` + `fullBackupContent` deny-all so ADB/cloud device backups can't exfiltrate wrapped keys with ciphertext; iOS vault dir flagged `isExcludedFromBackup = true` (Phase 3 backup replaces OS backup deliberately).

**Status — built.** `core:storage` ships this layer as a `VaultStorage(root: Path, fs = SystemFileSystem)` facade that owns `objects/`, `thumbs/`, `keys/`, `tmp/` beneath the **injected root**, exposing:

- the object store (sharded, atomic scoped write/read/delete/exists);
- the thumbnail store (flat `.tdchm`);
- the key-blob store (`readKeyStore`/`writeKeyStore`, null-if-absent);
- tmp scratch (`useTempFile`/`purgeTmp`), plus `useSpilledCopy` — copy a **one-shot** source into `tmp/` and hand the caller a re-openable view of it, deleted on return and on throw. Import needs it because the codec reads its payload twice while a provider stream may open only once; it is a fallback, never the default path.

**The import path carries two callbacks and no UI vocabulary.** `core:vault`'s import reports *bytes read so far* — cumulative and monotonic across the codec's two passes, so it ends at twice the payload size — and takes a predicate it polls on every read to stop early. Neither is a percentage, a total or a cancellation token, deliberately:

- the **denominator belongs to the caller**, which knows whether the platform declared a size at all. A file no picker will size gets an indeterminate indicator rather than a bar filling against a number we invented;
- the **stop predicate is the same check a lock already uses**. The encrypt is a blocking read loop, so cancelling the coroutine around it would only take effect at the end of the current file — minutes, for a large video. One predicate means a user cancel inherits the lock's fail-closed teardown exactly: no object bytes, no catalog row, no scratch.

Writes are atomic (temp sibling + `atomicMove`; **fsync-before-move is a documented v1 gap**), every op returns `Outcome`/`VaultError.Io` (no throw, nothing logged), and the module depends on `core:model` + `kotlinx-io` only — **not `core:crypto`** (`.dchm` bytes flow through as opaque sources/sinks). The Android backup-deny manifest + deny-all rule files landed here. **Resolved in the app module:** platform root-**resolution** (Android `filesDir` / iOS `Documents`) and the iOS `isExcludedFromBackup` runtime call — `VaultStorage` takes the resolved, backup-excluded root as a precondition. Seekable byte-range reads for the video/audio viewers are a Phase-1 storage primitive.

## 5. Catalog database

The exact DDL lives in the version-controlled SQLDelight `.sq` files in `core-db` — this section documents the design, not a copy of the schema.

Tables and purpose:

| Table       | Purpose                                                        | Encrypted columns                                                   | Plaintext columns (why)                                                                       |
|-------------|----------------------------------------------------------------|---------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `items`     | One row per vault object (files & notes; `kind` discriminates) | `enc_meta` (name, MIME, dims, duration, origin hint), `dek_wrapped` | `id`, `album_id`, `kind`, `size`, `created_at` — needed for sorting/paging without decryption |

`kind` is named by `VaultItemKind` in `core:model`: **`UNKNOWN = 0`, `FILE = 1`, `NOTE = 2`**. These are persisted values, so after v1.0 the mapping may only be *added to*, never renumbered — a renumbering silently reinterprets every existing row. Zero is deliberately not a real kind, so a defaulted or corrupted row decodes to `UNKNOWN` instead of masquerading as a file, and decoding an unrecognised code never throws: a row written by a newer build must not crash an older one.
| `albums`    | Albums/folders                                                 | `enc_name`                                                          | `id`, `kek_variant` (per-album password flag)                                                 |
| `audit_log` | Break-in events (failed unlocks, intruder captures)            | event payload                                                       | `id`, `timestamp`                                                                             |

- `enc_*` fields are AES-GCM under an MK-derived key; the DB file itself is not separately encrypted in v1 (application-layer field encryption is the guarantee; SQLCipher is a later defense-in-depth option).
- Search over encrypted names: an in-memory index built per unlock session (vault scale is thousands of items; linear decrypt-and-scan is fine — revisit if profiling disagrees).
- **Migration policy:** SQLDelight migrations are committed alongside schema changes; CI runs an upgrade test from every previously released schema version, and release checklist (§14) includes "old vault opens" verification. A failed migration must fail closed (refuse to run) rather than risk catalog corruption.

**Status — built.** `:core:db` ships schema v1 (`items` / `albums` / `audit_log`, `.sq` = canonical DDL) generating `DarkChamberDatabase`, plus `CatalogStore` — a thin `Outcome`-returning facade over an **injected `SqlDriver`** (per-table upsert/get/list/delete/count, a reactive `observeItems(albumId?): Flow<List<…>>`).

- Every column is either an opaque encrypted BLOB (`enc_meta`/`dek_wrapped`/`enc_name`/`enc_payload`) or a sanctioned plaintext index column — the catalog never interprets content.
- Failures map to **`VaultError.Db`** and the caller **fails closed** (`CatalogStore.create` refuses a half-open catalog); no exception crosses the boundary.
- Depends on **`core:model` + SQLDelight only — no `core:crypto`** (field encryption composes above it); `verifyMigrations` guards the committed v1 schema dump.
- The **`DriverFactory` `expect/actual`** (Android `AndroidSqliteDriver` / iOS `NativeSqliteDriver`) is the production seam, compile-gated here — resolving the `Context` and the `<root>/db/catalog.db` path (dir created via `core:storage`) lives in the **app module**; unit tests inject in-memory drivers.
- **`enc_meta` carries `(name, mime)` and nothing more for now.** Every richer field this section anticipates — dimensions, duration, capture date — needs a platform decoder to populate it, and a nullable field nothing ever sets is indistinguishable from a bug. They arrive with the decoders, in the cycle that adds thumbnails. What exists already is the part that makes adding them safe on vaults in the field: the blob is versionless CBOR, so its codec **ignores keys it does not recognise** and applies defaults for keys that are absent. A build that predates a field still reads rows written by one that has it, instead of failing perfectly intact items as unverifiable. (`.dchm` headers stay strict by contrast — an unexpected key *there* is a tampering signal.)
- Listings are **newest-first**. The catalog feeds a gallery, and what a user just added is what they expect to see without scrolling; the `created_at` index serves either direction, so this is ordering only — no schema change and no migration. Rows created in the same millisecond — the ordinary case when a batch import writes several at once — fall back to the item id, so the order is total and does not drift between re-runs of the query. The gallery keys its tiles by id and animates the ones that arrive; an unstable tie order would reshuffle the grid and animate items that never moved.

## 6. Private camera

Pipeline goal: sensor → JPEG/HEIC or MP4 bytes → encrypting sink → `objects/….dchm`. Plaintext never persisted.

- **Android (CameraX):** photos via `ImageCapture` in-memory callback → encrypt buffer → write. Video via `Recorder` writing to a `FileDescriptor` we own; practical v1 approach: record to a `tmp/` app-private file, then stream-encrypt + delete immediately (private-dir exposure only, disclosed); v1.x upgrade: `memfd_create`-backed descriptor or `MediaCodec`+`MediaMuxer` custom sink for zero-disk plaintext.
- **iOS (AVFoundation):** `AVCapturePhotoOutput` delivers in-memory `Data` → encrypt. Video via `AVAssetWriter` to app-private tmp → stream-encrypt + delete (same disclosed tradeoff; `AVAssetWriter` requires a file URL).
- EXIF policy: camera writes **no GPS** by default; optional "keep capture metadata (stored encrypted)" toggle.
- Thumbnails generated in-memory at capture, encrypted to `thumbs/`.

## 7. Viewers (per-type presenters over one blob store)

| Type               | Android                                                                                                                                        | iOS                                                                                                          |
|--------------------|------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Image              | Decrypt → `BitmapFactory.decodeByteArray` (subsampled) → Compose `Image`; Coil custom `Fetcher` keyed by file_id, memory-cache only            | Decrypt → `UIImage(data:)` / Compose                                                                         |
| Video/Audio        | Media3 **custom `DataSource`**: `open(dataSpec)` maps byte offset → chunk index, decrypts on demand → full seek support                        | `AVAssetResourceLoaderDelegate` serving decrypted byte ranges to `AVPlayer` (cleaner than a loopback server) |
| PDF                | v1: decrypt → `tmp/` file → `PdfRenderer` → delete on close. v1.x: `memfd_create` (NDK) for RAM-backed fd, or Pdfium wrapper accepting streams | `PDFDocument(data:)` — fully in-memory                                                                       |
| Other (docx/zip/…) | "Decrypt & open in…": decrypt → `tmp/` → `FileProvider` share intent → warning dialog → post-use cleanup + optional re-import of edits         | Same via `UIActivityViewController` + temporary file coordinator                                             |

**Status — built:** the screen protection, and the gallery those viewers will open from.

- **Screen protection is whole-app, not per-viewer.** `:androidApp` sets `FLAG_SECURE` unconditionally in `MainActivity.onCreate` (screenshots, screen recording and the app-switcher thumbnail); `:app`'s `MainViewController` lays a thick-material blur over the key window on `willResignActive` and removes it on `didBecomeActive`. Everything in the unlocked tree is vault content and the locked tree shows the recovery key, so nothing opts out — and a per-screen wrapper a new screen forgets is a silent leak. Screenshot *blocking* remains impossible on iOS (no `FLAG_SECURE` equivalent); the threat model says so and no UI copy may imply otherwise. The optional `userDidTakeScreenshotNotification` warning is still unbuilt.
- **`:feature:vault`** ships the gallery (an adaptive grid over `VaultObjects.observeItems`, with the square media slot reserved at the size a thumbnail will occupy) and single-file import through a `FilePicker` seam implemented per platform in `:app` — Android's Storage Access Framework, iOS's document picker opened **in place** rather than `asCopy`, so no plaintext copy is made before encryption. An item whose `enc_meta` will not open is rendered as an unverifiable tile rather than dropped: hiding it would conceal tampering.

**Not built here:** every viewer in the table above, and the seekable byte-range reads (§4) they need. Tapping a tile is deliberately inert until then.

## 8. Unlock, session & lock lifecycle

1. Cold start → PIN or biometric → unwrap MK → session object holds MK in memory.
2. Auto-lock: on background beyond grace period (default 30 s, configurable), on device lock, on demand. Lock = zeroize MK/DEK caches, purge `tmp/`, invalidate decrypted bitmap caches, pop viewers.
3. Compose side: single `VaultSession` state; every feature screen requires it — no screen can render decrypted content without a live session (compile-time enforced by API shape).

> **Status — built.** The composition root + cold-open exist in `:app`:
> `VaultBootstrap` resolves the app-private vault root, opens the catalog + storage, and
> creates/unlocks a `VaultSession` fail-closed (`Outcome`-typed — the first error aborts, never a
> half-open vault). **5b adds the real gate:** the Decompose `RootComponent` has exactly two children,
> `Locked` and `Unlocked`, and `Unlocked` is *constructed with* a live session — a decrypted-content
> screen cannot exist outside that subtree by construction (step 3). **Per-use biometric unlock** is
> cryptographic, not a UI checkpoint (step 1): the hardware MK-wrap key is auth-required
> (`setUserAuthenticationRequired` + a `BiometricPrompt.CryptoObject` on Android; a
> `.biometryCurrentSet` `SecAccessControl` + `LAContext` on iOS), with a passphrase fallback and, on
> biometric re-enrollment, a forced-passphrase path (§3.3). **Lock** (step 2) runs on the manual Lock
> button **and on background** (Android `onStop` guarded against config changes; iOS
> `didEnterBackground`): zeroize MK, purge `tmp/`, close the catalog driver, dispose the session
> scope, replace the whole `Unlocked` subtree. The **one** background that does not lock immediately
> is a system-UI handoff the app launched itself — Android's file picker is a separate activity, so
> reaching it stops us, and locking there would destroy the screen its result comes back to. The gate
> defers that lock while the handoff is outstanding and arms a 2-minute watchdog for a picker the
> user abandons; every other background locks at once (`SystemUiHandoff`, THREAT_MODEL invariants).
> **Long work is scoped to the session, not to a screen.** An import of fifty files belongs to the
> unlocked session and runs on the job the lock cancels *first*, so navigating away cannot abandon
> it while locking still ends it — before the MK it encrypts against is zeroized. Lock does not
> *wait* for work in flight: it runs on the UI thread, so awaiting a multi-second encrypt would
> freeze the app and delay zeroizing the key. It does not need to, because the guarded source aborts
> within a buffered read. Nothing about the work is persisted across the lock, so the user is warned
> *before* an import rather than told afterwards.
> Still **Phase 1**: the configurable auto-lock
> **timeout** + **device-lock** listener (step 2 hardcodes manual + on-background), and recovery-key
> **entry** on a fresh device (create still *displays* it). See
> `docs/verification/session-gate-manual.md`.

## 9. File metadata scrubber

Two entry points sharing one engine:

1. **On vault export/share** (default ON): strip before bytes leave the vault.
2. **Standalone tool** (works without importing): pick file(s) → inspect → output a clean copy to user-chosen location. Never modifies originals in place (avoids scoped-storage write friction entirely — output is a new file via SAF/`UIDocumentPicker`).

Engine: JPEG/PNG/WebP/HEIC — parse & drop EXIF/XMP/IPTC segments (GPS, serials, owner, thumbnails inside EXIF); re-encode only when a format demands it. MP4/MOV — drop `udta`/`©xyz` GPS and identifying atoms. PDF — strip Info dictionary + XMP (flagged "best effort": embedded objects may retain data). UI shows a **before/after diff** of removed fields — the diff *is* the product. Implementation: metadata-segment manipulation is pure byte-level Kotlin (`kotlinx-io`) in `commonMain`; use platform codecs only when re-encoding pixels.

## 10. Secure file shredder & additional security features

**Shredder — two honest modes, clearly separated in UI:**

- **Vault items → Crypto-shred (recommended, default):** destroy the item's wrapped DEK (and overwrite that tiny key record), delete ciphertext. Without the DEK the ciphertext is noise — genuinely irreversible, instant, works regardless of flash behavior. Panic wipe = crypto-shred of MK wraps.
- **External files → Best-effort overwrite:** single random-pass overwrite via SAF-granted write access, then delete. Shown with a **non-dismissable explanation**: flash wear-leveling/GC can retain old blocks; this reduces but cannot guarantee elimination of recoverability; full-disk encryption + factory reset is the only true erase. No multi-pass "DoD/Gutmann" options — they're theater on SSDs and we say so.

The same disclosure appears when a user imports a file and chooses "delete original."

**Phase 2 security features:**

- **Decoy vault:** duress PIN unwraps an alternate MK′ over a disjoint catalog; real and decoy stores are structurally identical on disk (no trivial "is there a hidden vault" flag).
- **Intruder log:** after N failed unlocks, capture front-camera frame (where OS permits) + timestamp, encrypted under MK, shown after next real unlock.
- **Per-album passwords:** album KEK = HKDF(MK, album_salt, album_password); items in the album wrap DEKs under album KEK.
- **Panic wipe:** confirmed gesture/duress-variant → zeroize + delete all MK wraps → full-vault crypto-shred.

## 11. Encrypted notes

Notes are vault items (`kind='note'`, `text/markdown`) in the same `.dchm` container — no separate crypto path. Compose editor with debounced autosave (encrypt full note on each save; notes are small). Title lives in `enc_meta`; body is the object payload. Attachments = links to other vault items. Search: in-memory per-session index (§5).

## 12. Cloud backup (Phase 3, paid unlock) — design sketch

- Providers: user's own Google Drive (`drive.appDataFolder` or visible encrypted folder — TBD, to be decided in an ADR; appData hides clutter, visible folder aids user trust/portability) and iCloud Drive.
- Upload unit = `.dchm` chunks → resumable by construction; the encrypted backup manifest is specified in [`FORMAT.md`](./FORMAT.md) §4 before Phase 3 ships.
- Restore path requires **recovery key only** (new device has no hardware wrap) — hence the recovery-key wrap of MK ships in the backup manifest.
- We never operate storage; OAuth tokens stay on-device. Store builds gate the feature behind one-time IAP; F-Droid build ungated.

## 13. Platform & policy compliance

- **Permissions:** camera, microphone (video), biometrics, photo-picker access. **No** `MANAGE_EXTERNAL_STORAGE`, no background location, no accessibility service, no usage access, no overlay (see ADR-001, ADR-002). Media access via Photo Picker (Android) / `PHPicker` (iOS) — no broad library permission needed for import.
- **Apple export compliance:** we use standard algorithms partly via non-OS libs (OpenSSL under cryptography-kotlin on some targets) → review annually; set `ITSAppUsesNonExemptEncryption` appropriately and file the French ANSSI declaration if distributing in France.
- **Google Play:** no Misrepresentation-risk features (no disguise icons); data-safety form = "no data collected/shared" (crash reporting opt-in only, self-hosted).

## 14. Build, test, release

- CI: assemble + unit tests (JVM fast path for `core-*`), iOS framework build, **format test vectors as a mandatory gate** (see [`FORMAT.md`](./FORMAT.md) §7), schema-migration upgrade tests from every released version, screenshot tests for critical flows, dependency review + `--verify-metadata` (supply-chain hygiene matters for a security app).
- **Reproducible builds** target from v1.x (pinned toolchains, deterministic flags) → prerequisite for F-Droid and for "verify the store APK matches GitHub."
- Release checklist per version: policy review, threat-model diff, format-version compatibility test (old vaults must always open), migration tests.
- `SECURITY.md`: disclosure process, PGP key, 90-day coordinated timeline. Audit scope (Phase 4): `core-crypto`, `FORMAT.md` conformance, and key lifecycle.

## 15. Repository layout

```
/                     GPLv3 LICENSE · TRADEMARK.md · SECURITY.md · README · ROADMAP.md
├─ core-model/  core-crypto/  core-storage/  core-db/  core-designsystem/
├─ feature-vault/  feature-camera/  feature-viewers/
├─ feature-notes/  feature-scrubber/  feature-shredder/
├─ feature-backup/            (Phase 3)
├─ app/                        shared composition root + VaultBootstrap + iOS "Shared" framework (built, 5a)
├─ androidApp/                 Application launcher (startKoin) + Compose host
├─ iosApp/                     SwiftUI host importing Shared.framework
├─ docs/
│   ├─ ARCHITECTURE.md  THREAT_MODEL.md  FORMAT.md  DESIGN.md
│   ├─ format-test-vectors/
│   └─ ADR/                    ADR-001 · ADR-002 · ADR-003 · …
└─ .github/workflows/
```

ADRs live in `docs/ADR/` — currently: ADR-001 (no auto-encryption of other apps' photos), ADR-002 (no app lock), ADR-003 (no sandboxing of other apps). Future candidates: Argon2 binding choice, Drive appDataFolder vs visible folder.
