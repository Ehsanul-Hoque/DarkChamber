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
| Camera            | CameraX / AVFoundation, directly, behind our own `CameraController` seam                                     | See §6                                                                                                                                          |
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
 │   ├─ thumbs/<file_id>.tdchm              # encrypted thumbnails (512px longest edge)
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

**The import path carries two callbacks and no UI vocabulary.** `core:vault`'s import reports *which phase it is in and how many bytes it has read within that phase*, and takes a predicate it polls on every read to stop early. Neither is a percentage, a total or a cancellation token, deliberately:

- the **denominator belongs to the caller**, which knows whether the platform declared a size at all. A file no picker will size gets an indeterminate indicator rather than a bar filling against a number we invented;
- the **phase says which denominator applies**, which is the one part of that arithmetic the caller cannot work out for itself — whether the payload had to be spilled is decided inside the import. Three phases, because an import does not read its payload once and the passes do not share a divisor:

  | phase | what it covers | divisor |
  |---|---|---|
  | copying | draining a one-shot source into `tmp/` | the payload size |
  | encrypting | the container's two passes | twice that |
  | previewing | the preview encoder's own reads | none |

  Bytes are cumulative **within** a phase and start again at each boundary: across the container's two passes the count must not restart, or one file's bar fills twice, and a count carried over a boundary would open the next phase at the payload size. The preview phase reports no bytes at all — how many passes an encoder makes is its own business, so there is nothing to divide by — and it is reported once per *attempt*, including for files that get no preview, because only the encoder knows which those are. A caller that wants user-facing words supplies its own; this module names reads, not waits.
- the **stop predicate is the same check a lock already uses**. The encrypt is a blocking read loop, so cancelling the coroutine around it would only take effect at the end of the current file — minutes, for a large video. One predicate means a user cancel inherits the lock's fail-closed teardown exactly: no object bytes, no catalog row, no scratch.

**The first open happens outside this module, and outlives the session on purpose.** A content provider may block inside its open for as long as it likes — Google Drive downloads the entire file there, measured at four to five minutes for 155 MB — and during that wait the import performs no reads, so the per-read predicate has nothing to land on. Abandoning a blocked call needs a second thread, which this module does not have and will not grow: it is synchronous and `Outcome`-returning by design. So the import accepts an **already-open source** for its first read, and the caller — which is coroutine-aware — performs that open where it can give up waiting. Only the first one: a caching provider serves every later open from its cache, and a streaming provider returns from the open at once. That leaves one residual, accepted rather than solved — a cache evicted part-way through an import puts a later open back into the provider's download, where only the per-read predicate can reach it and so cannot be abandoned. It costs a stop that waits; it cannot cost correctness, and the window is a cache eviction during a single file's import.

That open runs in a scope deliberately detached from the session. Cancelling it would not stop anything — the work is happening in the provider's process — and would discard the handle needed to close it, so this is the one place in the app that outlives a lock. What it holds is a file descriptor, never key material and never plaintext: an abandoned open is **closed unread**.

**Stopping an import does not stop the provider's transfer.** There is no API that can. The phone finishes fetching a file the user told us to stop, and the result is discarded; the descriptor is closed when it finally arrives, so nothing leaks. That is a real cost, stated rather than implied — the alternative is waiting the download out, which is the behaviour this replaced.

Writes are atomic (temp sibling + `atomicMove`; **fsync-before-move is a documented v1 gap**), every op returns `Outcome`/`VaultError.Io` (no throw, nothing logged), and the module depends on `core:model` + `kotlinx-io` only — **not `core:crypto`** (`.dchm` bytes flow through as opaque sources/sinks). The Android backup-deny manifest + deny-all rule files landed here. **Resolved in the app module:** platform root-**resolution** (Android `filesDir` / iOS `Documents`) and the iOS `isExcludedFromBackup` runtime call — `VaultStorage` takes the resolved, backup-excluded root as a precondition. Seekable byte-range reads for the video/audio viewers are a Phase-1 storage primitive.

## 5. Catalog database

The exact DDL lives in the version-controlled SQLDelight `.sq` files in `core-db` — this section documents the design, not a copy of the schema.

Tables and purpose:

| Table       | Purpose                                                        | Encrypted columns                                                   | Plaintext columns (why)                                                                       |
|-------------|----------------------------------------------------------------|---------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `items`     | One row per vault object (files & notes; `kind` discriminates) | `enc_meta` (name, MIME, dims, duration, origin hint), `dek_wrapped` | `id`, `album_id`, `kind`, `size`, `created_at` — needed for sorting/paging without decryption |

`kind` is named by `VaultItemKind` in `core:model`: **`UNKNOWN = 0`, `FILE = 1`, `NOTE = 2`**. These are persisted values, so after v1.0 the mapping may only be *added to*, never renumbered — a renumbering silently reinterprets every existing row. Zero is deliberately not a real kind, so a defaulted or corrupted row decodes to `UNKNOWN` instead of masquerading as a file, and decoding an unrecognised code never throws: a row written by a newer build must not crash an older one.
| `albums`    | Albums (see §5.1)                                              | `enc_name`                                                          | `id`, `kek_variant` (reserved for a future per-album key), `created_at`                        |
| `audit_log` | Break-in events (failed unlocks, intruder captures)            | event payload                                                       | `id`, `timestamp`                                                                             |

- `enc_*` fields are AES-GCM under an MK-derived key; the DB file itself is not separately encrypted in v1 (application-layer field encryption is the guarantee; SQLCipher is a later defense-in-depth option).
- Search over encrypted names: **no index** — the listing a screen is already drawing holds its names decrypted, so a query is a case-folded substring scan over that (vault scale is thousands of items). Built this way rather than as a per-session index because an index is a second copy of every name to keep current across import, rename, move and shred, and the scan costs nothing a listing has not already paid. Revisit if profiling disagrees. Matching is a **case fold, not locale collation**, which is right for A–Z and not a promise about every script the app renders.
- **Migration policy:** SQLDelight migrations are committed alongside schema changes; CI runs an upgrade test from every previously released schema version, and release checklist (§14) includes "old vault opens" verification. A failed migration must fail closed (refuse to run) rather than risk catalog corruption.

**Status — built.** `:core:db` ships schema v1 (`items` / `albums` / `audit_log`, `.sq` = canonical DDL) generating `DarkChamberDatabase`, plus `CatalogStore` — a thin `Outcome`-returning facade over an **injected `SqlDriver`** (per-table upsert/get/list/delete/count, a reactive `observeItems(filter): Flow<List<…>>` over the three-case listing filter of §5.1, the batched item-to-album write, and the album listing that carries each album's count and cover).

- Every column is either an opaque encrypted BLOB (`enc_meta`/`dek_wrapped`/`enc_name`/`enc_payload`) or a sanctioned plaintext index column — the catalog never interprets content.
- Failures map to **`VaultError.Db`** and the caller **fails closed** (`CatalogStore.create` refuses a half-open catalog); no exception crosses the boundary.
- Depends on **`core:model` + SQLDelight only — no `core:crypto`** (field encryption composes above it); `verifyMigrations` guards the committed v1 schema dump.
- The **`DriverFactory` `expect/actual`** (Android `AndroidSqliteDriver` / iOS `NativeSqliteDriver`) is the production seam, compile-gated here — resolving the `Context` and the `<root>/db/catalog.db` path (dir created via `core:storage`) lives in the **app module**; unit tests inject in-memory drivers.
- **`enc_meta` carries `(name, mime)` and the item's wrapped thumbnail key.** The richer fields this section anticipates — dimensions, duration, capture date — each need a platform decoder to populate them, and a nullable field nothing ever sets is indistinguishable from a bug; they arrive with those decoders. What already exists is the part that makes adding them safe on vaults in the field: the blob is versionless CBOR, so its codec **ignores keys it does not recognise** and applies defaults for keys that are absent. A build that predates a field still reads rows written by one that has it, instead of failing perfectly intact items as unverifiable. (`.dchm` headers stay strict by contrast — an unexpected key *there* is a tampering signal.)
- **Why the thumbnail key lives in `enc_meta` rather than a column of its own.** A thumbnail is a separate `.dchm` object with its **own DEK** — `docs/FORMAT.md` §2 requires a fresh random key per object, and a preview is an object — so the item's row has to say how to open it. Putting it in the metadata blob adds no column and no migration, and it inherits the blob's forward compatibility: a build that predates previews reads such a row and simply finds no key. It is stored **wrapped**, never raw, because a listing decodes `enc_meta` for every row it draws; raw keys there would put one plaintext DEK per item into the gallery's state, and that state can outlive a lock. Deleting the catalog row still destroys both wrapped keys, so crypto-shredding is unaffected.
- Listings are **newest-first**. The catalog feeds a gallery, and what a user just added is what they expect to see without scrolling; the `created_at` index serves either direction, so this is ordering only — no schema change and no migration. Rows created in the same millisecond — the ordinary case when a batch import writes several at once — fall back to the item id, so the order is total and does not drift between re-runs of the query. The gallery keys its tiles by id and animates the ones that arrive; an unstable tie order would reshuffle the grid and animate items that never moved. The order a *user* chooses is applied above this (see §5.1): the catalog's job is a stable, total order to hand upwards, and equal timestamps being the ordinary case is why the tie-break matters there too — a date order falls back to the name, a name order to the date, and both to the id.

### 5.1 Albums

An album is a **label on an item, not a container that owns it**. `items.album_id` is a nullable column: an item is in at most one album, or in none. Everything below follows from that one sentence, and the places it is counter-intuitive are the places that needed deciding.

- **Three listings, not a nullable parameter.** What a screen is showing is the whole vault, one album, or the items in no album — and the third cannot be expressed as an id, because `WHERE album_id = ?` never matches `NULL`. So the filter is a type with three cases rather than an id that may be absent, and each case is a different query. The distinction is load-bearing in the other direction too: when items are *assigned*, `null` genuinely means "unfile these", so a nullable id there means something that it cannot mean when reading.
- **Deleting an album keeps its items.** They become unfiled; nothing of theirs is touched. This is the opposite of what folder semantics lead people to expect, and the reason is the **shred asymmetry**: deleting an item's catalog row *is* the crypto-shred, because the wrapped DEK that opens the file lives in that row. There is no trash to stage deletions in and no undo to offer, so folder-deletes-contents semantics here would destroy files irreversibly on a gesture aimed at a label. The album most likely to be deleted by mistake is a full one, because the grid shows its count. The confirmation therefore states where the items go rather than asking for permission.
- **The two writes are ordered, and the order is the design.** No transaction spans both, so deleting an album clears `album_id` on its items *first* and removes the album row second. Interrupted between them, that leaves an empty album: visible, harmless, deletable again. The other order would leave items pointing at an album that no longer exists — absent from every album view, absent from the unfiled listing because their `album_id` is not null, and reachable only through the whole vault.
- **Assignment is one write for a whole set**, not one per item. Every listing re-emits on any write to `items` and re-decrypts each row's metadata to draw it, so fifty separate writes would mean fifty notifications and fifty full re-decrypts of a list someone is looking at. The result reports how many rows actually changed: an item shredded between being selected and being moved is skipped rather than failing the batch, and a caller that cannot see the difference can only report a half-move as a clean one.
- **Counts and covers come from the listing, not from stored fields.** One query joins each album to its item count and newest item; a denormalised counter would be a second source of truth to keep correct across every import, move, shred and album delete. Album names are decrypted per emission — one small ciphertext per album, nothing per item — which is what makes the album grid affordable in a large vault.
- **An album is not a security boundary.** Everything in the vault is encrypted the same way whether or not it is in one, and album names are sealed under the master key like item metadata. The `albums.kek_variant` column exists so that a future per-album key would have somewhere to record itself; it does not mean one exists. No UI copy, and nothing here, may imply that filing something gives it extra protection.
- **A name that will not open is shown, never hidden.** An album whose `enc_name` fails its integrity tag still lists, still holds its items, and can still be deleted — hiding it would conceal tampering *and* strand whatever is filed inside it. It is drawn as unverifiable, in the same words an item in that state uses, and neither claims to know whether the cause was tampering or corruption. Renaming is the one action withheld: there is no name to start from, and offering to replace a name nobody has seen would be offering an edit the user cannot judge. Deleting is the action that helps there, and it destroys nothing.
- **Naming a filed item's album costs nothing per item.** The one listing that mixes filed and unfiled items shows which album a filed one is in, and the name comes from the album listing above — one small ciphertext per *album*, mapped in memory by id — never from a read of the item's own row. That bound is the whole reason the album surface is affordable in a large vault, and it is the thing to protect when anything new is added to a tile: a field that needs a second decrypt per row turns a fixed cost into one that grows with the vault. The album listing is subscribed **only** for that listing; inside an album every item is in that album and in the unfiled view none has one, so neither pays for a label it would never draw.
- **Ordering and searching by name cannot be queries.** A name lives inside `enc_meta`, which is opaque to the catalog, so only date, size and kind are available to SQL. The catalog therefore keeps serving its newest-first order and the chosen order and any name search are applied **above** it, to rows already decrypted for drawing. That is a performance decision rather than a convenience one: a listing re-decrypts every row's metadata on every emission, so keying the subscription on a query would mean one GCM open per row per character typed. The subscription is keyed by the listing filter alone, and the two controls are combined with its output.

## 6. Private camera

Pipeline goal: sensor → JPEG/HEIC or MP4 bytes → encrypting sink → `objects/….dchm`. Plaintext never persisted.

- **Android (CameraX):** photos via `ImageCapture` in-memory callback → encrypt buffer → write. Video via `Recorder` writing to a `FileDescriptor` we own; practical v1 approach: record to a `tmp/` app-private file, then stream-encrypt + delete immediately (private-dir exposure only, disclosed); v1.x upgrade: `memfd_create`-backed descriptor or `MediaCodec`+`MediaMuxer` custom sink for zero-disk plaintext.
- **iOS (AVFoundation):** `AVCapturePhotoOutput` delivers in-memory `Data` → encrypt. Video via `AVAssetWriter` to app-private tmp → stream-encrypt + delete (same disclosed tradeoff; `AVAssetWriter` requires a file URL).
- EXIF policy: camera writes **no GPS**; a "keep capture metadata (stored encrypted)" toggle belongs with the settings screen and does not exist yet.
- Thumbnails generated in-memory at capture, encrypted to `thumbs/`.

**Status — photos built; video not yet.**

- **Sensor → memory → `.dchm`, with no disk stage.** `:feature:camera` owns the screen and the save path; the platform camera is a `CameraController` seam the app module implements with CameraX and AVFoundation directly. On Android the JPEG plane is copied out of `ImageCapture`'s in-memory callback; on iOS `fileDataRepresentation()` is copied out of the `AVCapturePhoto`. Neither platform's save-to-file capture is used — CameraX's saver stages through a file even when handed a stream. The bytes reach `VaultObjects.importObject` as a source that re-reads the array in place, so the import's probe never spills them to `tmp/`, and the encrypted object, its catalog row and its encrypted thumbnail are written the same way an import's are.
- **Orientation is written in memory.** CameraX reports how far the frame must be turned; a small patcher writes that into the JPEG's own EXIF orientation tag, rewriting the tag in place where one exists or inserting a minimal EXIF block where none does, before the bytes are encrypted. On iOS the system's rotation coordinator sets the orientation at capture and the patcher leaves the photo alone. Pixels are never re-encoded.
- **No location, ever requested.** The app holds no location permission, so no capture can carry a position; the settings handed to the platform carry no metadata of ours. What the platform itself writes — orientation, capture time, device make and model — stays inside the encrypted object (`THREAT_MODEL.md`).
- **Only the app's own copy of a shot is cleared.** The arrays the save path holds are zeroed after the save, after a failure, and when a lock abandons it. The platform's capture buffers, and anything a decoder copies while making the thumbnail, are outside the app's reach.
- **A bounded queue, in the session's lifetime.** Each press captures; saves run one after another; at most three photos are held in memory at once, and the shutter disables at that bound until one completes. The queue lives in the unlocked session rather than in the screen, so closing the camera lets photos already taken finish, while a lock abandons them — nothing waits for a save while the app is in the background.
- **The camera opens on the listing it was opened from**, and files where an import from there would: one album into that album, the whole vault and the unfiled items into no album. Its bar counts that listing from the catalog's plaintext columns — an album's count from the album listing, the others from a filtered count query — so the count costs no decrypt per item.
- **The camera is open only while its screen is started**, and released on stop, on close and by the teardown a lock performs, so the platform's camera indicator never outlasts the viewfinder. On Android it is bound to a lifecycle of its own rather than the activity's, because the screen survives a rotation that recreates the activity. The preview is drawn inside the app's own window, so screenshot blocking and the app-switcher blur cover it like any other screen.
- **The last-shot tile is the gallery's thumbnail, display-only.** `:feature:vault` exposes `VaultThumbnail`, and the app module hands it to the camera as a slot, so the tile draws through the session's one image loader and its memory cache — the one the lock empties — rather than a second loader of the camera's own.

## 7. Viewers (per-type presenters over one blob store)

| Type               | Android                                                                                                                                        | iOS                                                                                                          |
|--------------------|------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Image              | Decrypt → `BitmapFactory.decodeByteArray` (subsampled) → Compose `Image`; Coil custom `Fetcher` keyed by file_id, memory-cache only            | Decrypt → `UIImage(data:)` / Compose                                                                         |
| Video/Audio        | Media3 **custom `DataSource`**: `open(dataSpec)` maps byte offset → chunk index, decrypts on demand → full seek support                        | `AVAssetResourceLoaderDelegate` serving decrypted byte ranges to `AVPlayer` (cleaner than a loopback server) |
| PDF                | v1: decrypt → `tmp/` file → `PdfRenderer` → delete on close. v1.x: `memfd_create` (NDK) for RAM-backed fd, or Pdfium wrapper accepting streams | `PDFDocument(data:)` — fully in-memory                                                                       |
| Other (docx/zip/…) | "Decrypt & open in…": decrypt → `tmp/` → `FileProvider` share intent → warning dialog → post-use cleanup + optional re-import of edits         | Same via `UIActivityViewController` + temporary file coordinator                                             |

**Status — built:** the screen protection, and the gallery those viewers will open from.

- **Screen protection is whole-app, not per-viewer.** `:androidApp` sets `FLAG_SECURE` unconditionally in `MainActivity.onCreate` (screenshots, screen recording and the app-switcher thumbnail); `:app`'s `MainViewController` lays a thick-material blur over the key window on `willResignActive` and removes it on `didBecomeActive`. Everything in the unlocked tree is vault content and the locked tree shows the recovery key, so nothing opts out — and a per-screen wrapper a new screen forgets is a silent leak. Screenshot *blocking* remains impossible on iOS (no `FLAG_SECURE` equivalent); the threat model says so and no UI copy may imply otherwise. The optional `userDidTakeScreenshotNotification` warning is still unbuilt.
- **`FLAG_SECURE` is not the whole of window protection.** Android also builds a structure of the foreground window for its assist and autofill services, which run in other processes, and the secure flag does not stop it: with the flag set, a device showed a fill request leaving the app with one fillable field in it. Every window the app opens therefore excludes itself from the autofill and content-capture structures — the activity in `MainActivity.onCreate`, and each dialog through `core:designsystem`'s `ExcludeFromPlatformStructures`, because a dialog is its own window and inherits nothing. An architecture rule fails the build when a dialog is opened without it. The cost is that a password manager cannot fill the passphrase; `THREAT_MODEL.md` records why that trade is the right way round, and what the measurement showed.
- **The gallery's image loader keeps no disk cache, and its memory cache dies with the session.** Everything it loads is decrypted vault content, so a disk cache would be plaintext on storage — and that is the *default* a plainly-built Coil loader gives you, silently, with every tile still rendering correctly. It is therefore disabled twice over (no cache instance, and the policy disabled), and an architecture rule fails any image loader built anywhere without both. The memory cache is the same hazard one layer up: it exists so scrolling does not re-decrypt, which means a lock that left it full would keep handing out pictures of someone's files while the app looked locked. It is emptied in the session teardown, beside the `tmp/` purge — not on the lock path, so that a subtree destroyed without ever being resumed is covered too.
- **A tile with no preview is an ordinary tile.** Only images produce one; everything else, and any image the platform decoder declines, shows its type badge. A thumbnail is a cache, never evidence — an import is never failed because a preview could not be made.
- **`:feature:vault`** ships the gallery (an adaptive grid over `VaultObjects.observeItems`, newest first, with previews drawn in the reserved square media slot) and **batch import** through the `FilePicker` / `PhotoPicker` seams implemented per platform in `:app` — Android's Storage Access Framework and photo picker, iOS's document picker opened **in place** rather than `asCopy`, so no plaintext copy is made before encryption. A pick may select many files; they are encrypted one at a time in bounded RAM, a file that cannot be read is recorded without ending the batch, and a stop takes effect **within** a file rather than after it. An item whose `enc_meta` will not open is rendered as an unverifiable tile rather than dropped: hiding it would conceal tampering. Where the platform will take it, the finished summary offers to delete the imported originals — never without the disclosure, and never where the offer could not be honoured.
- **The album surface sits on the same screen, not a second one.** One item grid serves the whole vault, one album and the unfiled items (§5.1); which it shows is fixed when it is built, so changing it is navigation rather than a state change and a selection can never straddle two listings. The album grid is its own screen: a tile per album with its count and its newest item as a cover, plus an entry to the unfiled items — which is deliberately **not** drawn as an album, because it has no row, no id and no name, and inventing one would be a fiction every later surface had to keep. From the screen showing one album, that album can be renamed or deleted; from any listing, selected items can be moved into an album or taken out of one, and an import lands in the album being viewed. Moving is a move: an item is in one album at a time, so nothing is copied and the copy says so.
- **An empty grid means three different things**, and saying the wrong one is worse than saying nothing: a vault with no files at all needs to be told how to add some, an album with nothing in it is perfectly ordinary, and nothing unfiled means everything has been filed — a success, which must not read as a shortage.

**Not built here:** every viewer in the table above, and the seekable byte-range reads (§4) they need. Tapping a tile is deliberately inert until then. Sorting and search over the listing are not built either.

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

The same disclosure appears when a user imports a file and chooses "delete original" — **built**, and offered far less often than it is withheld. The app can only ask the platform that owns the file: a document the user picked through the Storage Access Framework may be deleted if its provider advertises that it can — **including cloud providers: Google Drive does**, which was assumed otherwise until a device run showed the offer appearing for a Drive document. The flag is only ever an advertisement, and a provider may still refuse the call it advertised. Items chosen from a photo library cannot be deleted at all on either platform — on Android they arrive as read-only picker URIs that are neither documents nor MediaStore rows, and on iOS deleting would need the photo-library authorization the permission-less picker exists to avoid. So "no offer" is the ordinary outcome, and it is shown as nothing at all rather than a disabled control. What is offered covers only files that actually landed in the vault, the disclosure cannot be dismissed — only answered — and the result is reported as a count, because a provider can still refuse a delete it advertised.

**Phase 2 security features:**

- **Decoy vault:** duress PIN unwraps an alternate MK′ over a disjoint catalog; real and decoy stores are structurally identical on disk (no trivial "is there a hidden vault" flag).
- **Intruder log:** after N failed unlocks, capture front-camera frame (where OS permits) + timestamp, encrypted under MK, shown after next real unlock.
- **Per-album passwords:** album KEK = HKDF(MK, album_salt, album_password); items in the album wrap DEKs under album KEK. **Not built** — albums today are organisation only (§5.1), and `albums.kek_variant` exists so that this can arrive without a migration rather than because it has.
- **Panic wipe:** confirmed gesture/duress-variant → zeroize + delete all MK wraps → full-vault crypto-shred.

## 11. Encrypted notes

Notes are vault items (`kind='note'`, `text/markdown`) in the same `.dchm` container — no separate crypto path. Compose editor with debounced autosave (encrypt full note on each save; notes are small). Title lives in `enc_meta`; body is the object payload. Attachments = links to other vault items. Search: in-memory per-session index (§5).

## 12. Cloud backup (Phase 3, paid unlock) — design sketch

- Providers: user's own Google Drive (`drive.appDataFolder` or visible encrypted folder — TBD, to be decided in an ADR; appData hides clutter, visible folder aids user trust/portability) and iCloud Drive.
- Upload unit = `.dchm` chunks → resumable by construction; the encrypted backup manifest is specified in [`FORMAT.md`](./FORMAT.md) §4 before Phase 3 ships.
- Restore path requires **recovery key only** (new device has no hardware wrap) — hence the recovery-key wrap of MK ships in the backup manifest.
- We never operate storage; OAuth tokens stay on-device. Store builds gate the feature behind one-time IAP; F-Droid build ungated.

## 13. Platform & policy compliance

- **Permissions:** camera, microphone (video), biometrics, photo-picker access. The camera's hardware features are declared *not required*, so a device without a camera still installs and the vault works there. A library's own manifest can add a permission the app never asked for — CameraX brings in a media library that asks for network state — and such a permission is removed in the app's manifest rather than allowlisted. **No** `MANAGE_EXTERNAL_STORAGE`, no background location, no accessibility service, no usage access, no overlay (see ADR-001, ADR-002). Media access via Photo Picker (Android) / `PHPicker` (iOS) — no broad library permission needed for import.
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

ADRs live in `docs/ADR/` — currently: ADR-001 (no auto-encryption of other apps' photos), ADR-002 (no app lock), ADR-003 (no sandboxing of other apps), ADR-004 (no `INTERNET` permission in v1). Future candidates: Argon2 binding choice, Drive appDataFolder vs visible folder.
