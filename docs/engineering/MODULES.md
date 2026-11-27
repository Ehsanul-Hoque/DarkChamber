# Modules & Dependency Rules

> Part of the [Engineering Guide](./README.md).

## 1. Modularization strategy

DarkChamber is a multi-module Gradle project. Isolation is real (compile-time), not documentary.

| Category        | Modules                                                                                                                                          | Depends on                                | May be depended on by    |
|-----------------|--------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------|--------------------------|
| **App**         | `:app` (shared composition root), `:androidApp` (launcher), `:iosApp` (SwiftUI host)                                                             | everything (composition root)             | nothing                  |
| **Feature**     | `:feature:vault`, `:feature:camera`, `:feature:viewers`, `:feature:notes`, `:feature:scrubber`, `:feature:shredder`, `:feature:backup` (Phase 3) | `core:*`, other features' **`:api`** only | app                      |
| **Core**        | `:core:model`, `:core:crypto`, `:core:storage`, `:core:db`, `:core:vault`, `:core:designsystem`, `:core:logging`, `:core:testing`                | other `core:*` (acyclic)                  | features, app            |
| **Build logic** | `:build-logic`                                                                                                                                   | — (included build)                        | all modules (as plugins) |

### Core modules, by concern

- `core:model` — pure Kotlin domain types (`VaultItem`, `Album`, `Note`), MIME handling. Depends on nothing. It is also where a type shared by *all three* layers has to live: the listing filter (whole vault / one album / unfiled) is named here rather than in `core:db`, because the catalog is an `implementation` dependency of `core:vault` and a type declared there cannot reach a feature.
- `core:crypto` — the `.dchm` codec (normative spec: [`../FORMAT.md`](../FORMAT.md)), key hierarchy, KDF, DEK lifecycle, `CryptoEngine` interface wrapping `cryptography-kotlin`, `expect/actual SecureKeyStore` (Keystore/Secure Enclave). **The only module allowed to touch cipher primitives.**
- `core:storage` — app-private file layout, atomic writes, `tmp/` hygiene, thumbnail store. **The only module allowed to touch the vault's filesystem.** The one carve-out is the app module's platform boundary — the root resolver (`filesDir` / `NSDocumentDirectory`) and the iOS file picker, which reads a user-picked file at a security-scoped path that is outside the vault root and never written to. No feature module touches a filesystem API at all. *Built:* `VaultStorage(root: Path, fs = SystemFileSystem)` owns `objects/ thumbs/ keys/ tmp/` under an injected root, exposing:
    - object store — `writeObject`/`readObject`/`deleteObject`/`objectExists`, sharded, scoped atomic temp+`atomicMove`; and `openObjectReader` — an `ObjectReader` with the object's size and reads at any position, through a small `expect/actual` over `FileChannel` (Android) and `pread` (iOS), since kotlinx-io's sources only go forwards;
    - thumbnail store — `writeThumbnail`/… flat `.tdchm`;
    - key-blob store — `readKeyStore` (null-if-absent) / `writeKeyStore`;
    - tmp scratch — `useTempFile` (auto-delete) / `purgeTmp`.

    All ops return `Outcome`/`VaultError.Io` (no throws, nothing logged); depends on `core:model` + `kotlinx-io` only — **not `core:crypto`** (`.dchm` bytes flow through as opaque source/sink). Platform root-resolution + iOS `isExcludedFromBackup` live in the app module.
- `core:db` — SQLDelight schema (`.sq` files are the canonical DDL), drivers per platform, migrations. *Built:* schema v1 (`items`/`albums`/`audit_log`) generates `DarkChamberDatabase`; `CatalogStore(driver: SqlDriver)` is a thin `Outcome`-returning facade —
    - items — `upsertItem`/`getItem`/`listItems(filter)`/`deleteItem`/`countItems` + reactive `observeItems(filter): Flow`, where the filter is the three-case type from `core:model` (the unfiled case cannot be an id — `WHERE album_id = ?` never matches `NULL`), plus `updateItemAlbum(ids, albumId?)`, one write for a whole set inside a transaction, and `clearAlbumOnItems`;
    - albums — `upsertAlbum`/`getAlbum`/`listAlbums`/`deleteAlbum`, and the listing the album grid draws: one query joining each album to its item count and newest item, so neither is a stored field to keep correct; audit — `appendAudit`/`listAudit(limit)`;
    - `create(driver)` runs schema create/migrate **fail-closed** → `VaultError.Db`; every op catches driver failures → `Db`, no throw crosses.

    Columns are opaque encrypted BLOBs (`enc_meta`/`dek_wrapped`/`enc_name`/`enc_payload`) + sanctioned plaintext index columns only; rows carry ids + opaque blobs (never decrypted content). Depends on `core:model` + SQLDelight only — **not `core:crypto`** (field encryption composes above). The `DriverFactory` `expect/actual` (Android `AndroidSqliteDriver` / iOS `NativeSqliteDriver`) is the production seam; `Context` + `<root>/db/catalog.db` path resolution lives in the app module. `verifyMigrations` guards the committed v1 dump.
- `core:vault` — the compose-the-cores layer: the one module that joins `core:crypto` (session/codec), `core:storage` (objects), and `core:db` (catalog rows) into vault-object operations. *Built:* `VaultObjects(session, storage, catalog, codec, clock, random)` —
    - `importObject` — stream-encrypts a source into a `.dchm` object atomically, mints a per-object DEK, seals a minimal `enc_meta` ({name,mime}) under MK, writes the `items` row (fail-closed: a row failure deletes the object);
    - `openObject` — streams the container back to a sink; `openMeta` — reads {name,mime} from the row without touching the object;
    - `openRange` — a `RangeReader` over an object's plaintext: its length, known at open, and reads at any position that decrypt only the chunk each lands in, the key unwrapped per chunk and never held, at most two decrypted chunks kept and zeroed, every read checked against the session, and a count of chunks decrypted (a number only, for measuring). Import uses it to take a video's preview from the object it has just written, and the viewer's video and audio players stream through it;
    - `shredObject` — crypto-shreds (delete the row/DEK first, best-effort delete the bytes);
    - albums — `createAlbum`/`renameAlbum`/`deleteAlbum` (names sealed under MK like item metadata) and `observeAlbums`, which opens one small ciphertext per album and nothing per item; `assignItems(ids, albumId?)` files or unfiles a whole set in one write and reports how many rows actually changed. Deleting an album **keeps its items** — they become unfiled — and clears them *before* removing the album row; both the reason and the ordering are in [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §5.1, and the ordering is pinned by tests that inject a driver failure between the two writes. An import can name its destination album, so an item is filed as it is created rather than written unfiled and moved afterwards.
    - `observeItemCount(filter)` — a live count for the whole vault, the unfiled items or one album, read from the catalog's plaintext columns: it opens nothing, however many items or emissions (pinned by counting the crypto engine's opens).

    Everything is `Outcome`-typed and bounded-RAM (O(chunkSize)); depends on `core:model`+`crypto`+`storage`+`db` and imports **no cipher primitive** (`.dchm` bytes stay opaque, crypto stays behind `DchmCodec`/`VaultSession`).
- `core:designsystem` — theme + tokens per [`../DESIGN.md`](../DESIGN.md), shared Compose components. *Built:* `DarkChamberTheme` (semantic color/type/shape/spacing/motion roles — consumers read roles, never raw hex — plus a Material 3 substrate mapped from the tokens) and the **`Aperture`** signature (the reserved full-circle unlock affordance, §7, also the camera's shutter), plus the icon set as Kotlin `ImageVector`s handed out through composables that take a theme role — the camera's marks among them (camera, close, the three flash states, switch camera, the torch on and off, the microphone on and off), the viewer's (back, details, delete) and the player's (play, pause, replay, rotation, brightness, volume and muted), copied unchanged from Material Symbols exports and pinned by exact path counts. Compose artifacts are re-exported as `api`, so every feature inherits one Compose version and declares none itself. Applied via the `darkchamber.kmp.compose` convention plugin. Still ahead: Plex fonts.

    **Secure screens are not here.** `FLAG_SECURE` and the iOS snapshot blur are whole-app properties set by the platform hosts (`:androidApp`'s `MainActivity`, `:app`'s `MainViewController`), not per-screen wrappers: everything in the unlocked tree is vault content and the locked tree shows the recovery key, so there is nothing to opt out of, and a wrapper a new screen forgets to apply is a silent leak.
- `core:logging` — Kermit setup (see [`PATTERNS.md`](./PATTERNS.md) §4 — no Crashlytics, no remote anything). *Built:* `initLogging()` (Kermit + platform writer only) and `redactCause(VaultError): String` / `logVaultError(stage, error)` — the hard-rule-#4 boundary that reduces any `VaultError` to a content-free code (`Io`/`Db`/`Crypto.*`), never the carried cause message, a path, an id, or vault content — and `logMeasurement`, a line built only from a name and numbers, for measuring a player without logging what it plays.
- `core:testing` — test support, only ever a test dependency. *Built:* the Compose UI-test helpers — `UiTest`, the base class every UI test extends (an `expect`/`actual` class whose Android side carries Robolectric's runner, so one test in `commonTest` runs on the Android host and natively on the iOS simulator, while a module's other host tests stay plain JUnit); `setThemedContent` (the content inside `DarkChamberTheme`); `hasRole` and `onButton` (the button with exactly that label, enabled or not — never a title sharing its words); and `assertNoText` (no node shows the text, alone or inside a sentence). It exports Compose's test API, and depends on `core:designsystem` for the theme. A module gets all of it by applying `darkchamber.compose.uitest`. Planned: shared fakes, test dispatchers and the format test-vector loaders. The picker fakes stay in `:feature:vault`'s tests, since they implement that feature's own seams and a core module may not depend on a feature.

### Feature modules

- `:feature:vault` — the gallery, import, the album surface, the viewer and delete. *Built:* a public `api` package — the component contracts and the state they render, the seams the app implements (pickers, image decoder, players and display, PDF renderer, link opener), the text page's public types (contents internal) — plus the factories `vaultGalleryComponent(...)`, `vaultAlbumsComponent(...)` and `vaultViewerComponent(...)`; everything else is `internal`. It depends on **`core:vault` only** for vault access: no cipher primitive, no filesystem API, no SQLDelight type is reachable from it. Outside libraries: Coil (the gallery's loader, disk cache off) and `org.jetbrains:markdown` (a pure-Kotlin parser; the drawing is the feature's own Compose code — no web view or platform HTML engine). **It never navigates**: opening an album, the unfiled items, the camera or an item, and leaving an album that no longer exists, all leave through callbacks the host supplies at construction (an item opens with the grid's order and search, handed over in memory, and the viewer's back reports the item last viewed). Single-module for now; the `api`/`impl` split (§2) happens when a second feature needs the contract, and the `internal` discipline makes that a folder move.

    Its `api` also exposes **`VaultThumbnail(itemId, imageLoader)`** — one item's encrypted preview, for a screen outside this feature that has only an id (the camera's last-shot tile). It draws through the loader it is handed, which must be the session's one loader, so no second cache of decrypted previews exists for the lock to miss; a preview that fails its integrity tag draws the same damaged mark a gallery tile does, and says so to a screen reader.
- `:feature:camera` — the private camera, photos and video. *Built:* a public `api` package — the **`CameraController` seam** the app implements per platform (an in-memory `capture`, a `record` into a file it is handed, the preview as a composable), the `ClipFiles` / `FreeSpace` seams, the component contracts, and the factories `cameraSession(...)` (built once per unlock) and `cameraComponent(...)`. Everything else is `internal`: the photo save path (in memory, its arrays zeroed on every path), the one-clip-at-a-time **video lane** and the per-screen **recorder** (see [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §6). It depends on **`core:vault` only** — not on `:feature:vault`; the last-shot thumbnail arrives from the host as a slot and a tap on it leaves through a callback with the item's id. Two construction rules are the host's to keep: the vault comes from the session scope, and the `CameraSession` is built from the **session's feature scope, not the screen's**, so closing the camera lets a capture already taken finish while only a lock abandons one.

### Verification modules

- `:konsist` — a JVM test-only module holding the architecture rules (§4.2). No convention plugin: there is exactly one consumer, so it applies the Kotlin JVM plugin directly, and a `darkchamber.konsist` plugin would be indirection without a payoff.

### App modules

- `:app` — the shared **composition root** (built). It owns:
    - the Koin graph — `commonAppModule` + `androidMain`/`iosMain` platform modules binding every seam: the vault root, `SecureKeyStore`, `BiometricAuthenticator`, the catalog driver, the pickers, the thumbnail encoder, the camera (CameraX / AVFoundation: photos into memory, video only into the one `tmp/` file it is handed) and free space, the players and display (Media3 / `AVPlayer`, reading only through the item's range reader), the PDF renderers (an anonymous memory file + `PdfRenderer` on Android 11+, CoreGraphics over `NSMutableData` on iOS — never a file) and the link openers (a browsable `VIEW` intent / `UIApplication.openURL`, each re-checking the address). The viewer's seams and the camera are resolved optionally — without one the page shows its badge or the camera reports itself unavailable — so a platform-module test asserts each platform binds the real one. The host hands the viewer the subtree's own lock, which it calls once an address has been opened. How each platform side behaves is in [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §6–§7;
    - the platform vault-root resolution — Android `filesDir` / iOS `Documents` + `isExcludedFromBackup`;
    - `VaultBootstrap` — the fail-closed cold-open (`createVault` / `unlock` / `unlockWithBiometric` / `enrollBiometric` / `probe`);
    - the session gate + first shared Compose UI — the Decompose `RootComponent` (`Locked | Unlocked`, `Unlocked` constructed with a live session), the `LockedComponent` state machine, the lock/create/recovery/unlock screens on `DarkChamberTheme`, the lock teardown + on-background lock, and the platform biometric authenticators (`BiometricPrompt` / `LAContext`);
    - the iOS surface — `iosMain` exports the **`Shared`** static umbrella framework (`export`s `core:model`/`core:crypto`/`core:db`) that `:iosApp` links, plus `MainViewController()` (the Compose gate host).

    Composes `core:crypto` **only via `CryptoEngine`/`SecureKeyStore`/`BiometricAuthenticator`** — no cipher primitive imported here — and adds no network dependency.
- `:androidApp` — thin launcher: `DarkChamberApp : Application` runs `initLogging()` + `startKoin { androidContext(this); modules(appModules()) }`; `MainActivity` is a **`FragmentActivity`** (required by `BiometricPrompt`) hosting the Decompose root via `retainedComponent` (session survives config changes, never persisted) + `RootContent`, and publishes itself to the `ActivityHolder` for the biometric prompt, the pickers, the player's display and the link opener. Only allowlisted permissions — `USE_BIOMETRIC` (+ legacy `USE_FINGERPRINT`), `CAMERA` and `RECORD_AUDIO`, with the camera and microphone hardware declared not required; a network-state permission a CameraX dependency brings in is removed in the manifest, as are the network-state and wake-lock permissions Media3 brings. No `INTERNET`.
- `:iosApp` — SwiftUI host importing `Shared.framework`; `ContentView` hosts `MainViewController()` (the shared Compose gate) via `UIViewControllerRepresentable`. An earlier diagnostic self-check probe was removed.

There is deliberately **no `core:network` in v1** — Phases 0–2 have zero network features, and v1 ships without the `INTERNET` permission (ADR-004). The module appears in Phase 3 alongside `:feature:backup`.

## 2. The api/impl split

A feature consumed by other features splits in two:

```
:feature:vault
  ├── api    → interfaces, exposed models, navigation contract (public surface)
  └── impl   → data/domain/presentation implementation (hidden)
```

**Rule:** feature A depends on `feature:B:api` when it needs B's models/contracts; only the app module depends on any `:impl`. Leaking an internal is a *compile error*, not a review nitpick. A leaf feature with no cross-feature consumers stays a single module until something needs it — don't split preemptively. (Likely first split: `:feature:vault:api`, since viewers, notes, and shredder all consume vault items.)

## 3. Feature-internal layers

```
feature/
├── data/            # repository impls; storage/db access via core:*
├── domain/          # use cases, models, repository interfaces — pure Kotlin
├── di/              # this feature's Koin module
└── presentation/    # MVI ViewModels + Compose screens + Decompose components
```

Direction: `presentation → domain ← data`; `domain` depends on nothing in the feature. DTOs/row types never escape `data`.

## 4. Enforcement

### 4.1 Compile-time (Gradle graph)

Cross-module isolation is enforced by what each `build.gradle.kts` declares. If a module doesn't declare a dependency, it cannot import from it — full stop.

### 4.2 Architectural (Konsist)

Konsist runs as JUnit tests in `:konsist` and asserts what the compiler can't. **Built:** the
security-invariant rules below, running in `pr.yml`'s enabled build job — ahead of the assemble, so a
hard-rule violation fails a PR in seconds rather than minutes. Layer/naming rules (use cases in
`..domain.usecase..`, DTOs confined to `..data..`) land with the rest of the quality tooling.

Five rules are denylists over production imports:

| Rule | Confined to | Guards |
|------|-------------|--------|
| Cipher primitives — `dev.whyoleg.cryptography`, `javax.crypto`, `java.security`, `org.bouncycastle`, `platform.CoreCrypto`, `platform.Security` | `core:crypto` | hard rule #2 |
| Networking — `java.net`, `javax.net`, `okhttp3`, `retrofit2`, `io.ktor`, `platform.Foundation.NSURL{Session,Request,Connection}`, `platform.Network` | nowhere | hard rule #3 / ADR-004 |
| Analytics & crash SDKs — Firebase, Crashlytics, Sentry, Amplitude, Mixpanel, AppsFlyer, GMS analytics | nowhere | hard rule #4 |
| Shared storage — `android.provider.MediaStore`, `android.os.Environment`, anything matching `*ExternalStorage*` | `core:storage` | hard rule #1 |
| The logger — `co.touchlab.kermit` | `core:logging` | hard rule #4 (redaction boundary) |

The rest read production source text, for hazards an import list cannot see:

- every image loader is built with its disk cache off (hard rule #1);
- every window the app opens — each activity, dialog, menu and sheet — keeps itself out of the platform's
  autofill and content-capture structures;
- no Android source declares a `PlatformContext` in the Koin graph (it recursed on launch once);
- the record of what did not make it into the vault, kept above the session gate, holds counts, never
  content (hard rule #4);
- screen state in a feature names no decrypted-content type — no decoded picture, player, PDF page or
  decoded text, nor a call that produces one;
- nothing opens a web address except the confirmed link opener: Compose's `LinkAnnotation.Url`,
  `UriHandler`/`LocalUriHandler` and `openUri` appear nowhere in production code, since each opens an
  address with no confirmation and no lock; the platforms' own (`ACTION_VIEW` or its action name
  written out, `openURL`, `CustomTabsIntent`, `SFSafariViewController`) appear only in the two link
  openers and in the iOS camera's link to the app's Settings page. The Android opener builds its intent
  in a function private to its file, so the rest of the app can reach it only through the opener.

Two details that make these real rather than decorative:

- **Production is derived from the path** (`src/<sourceSet>/` where the set is `main` or ends in
  `Main`), not Konsist's own source-set notion, because KMP puts production code in
  `commonMain`/`androidMain`/`iosMain`. Test code is deliberately out of scope.
- **Every rule is an absence assertion**, so a scope that resolved to zero files would pass every
  one while checking nothing. A companion test asserts the scan reaches every module holding Kotlin
  sources and sees a meaningful number of imports — **a new module must be added to that list**, or
  the invariants silently do not cover it. A second test drives each denylist over synthetic import
  names to prove it matches its targets *and* stops at the right edge: `NSURLSession` is forbidden
  while `NSURL`, which the iOS vault root resolver needs, is not, and the Storage Access Framework is
  allowed because it is how the user hands us a file to import.

These make the [`THREAT_MODEL.md`](../THREAT_MODEL.md) invariants self-policing: violating "all crypto goes through `CryptoEngine`" or "plaintext never touches shared storage" fails CI.

## 5. build-logic & convention plugins

An included `:build-logic` build centralizes KMP/Compose/quality setup into convention plugins, so per-module `build.gradle.kts` files stay tiny:

```
build-logic/src/main/kotlin/
├── darkchamber.kmp.library.gradle.kts      # built — KMP targets + common deps
├── darkchamber.kmp.compose.gradle.kts      # built — kmp.library + Compose Multiplatform
├── darkchamber.kmp.feature.gradle.kts      # built — kmp.compose + Decompose/Koin/designsystem/logging
├── darkchamber.compose.uitest.gradle.kts   # built — opt-in: Compose UI tests on the Android host + iOS simulator
├── darkchamber.android.application.gradle.kts   # built
└── darkchamber.quality.gradle.kts          # planned — detekt + ktlint + kover, uniform
```

`darkchamber.compose.uitest` is applied by each module that has Compose UI tests, beside its
`darkchamber.kmp.*` plugin. It adds `:core:testing` to `commonTest`, gives the Android host test the merged
manifest and resources Robolectric reads (with the test activity's manifest, and Espresso 3.7.0 in place of
the 3.5.0 Compose's test library brings, which fails under Robolectric), and opts the test source sets into
Compose's experimental test API. A UI test is then a `commonTest` class extending `UiTest`.

There is deliberately **no `darkchamber.konsist` plugin**: `:konsist` is the single consumer of those
rules and applies the Kotlin JVM plugin directly.

`darkchamber.kmp.feature` also carries `-lsqlite3` in the native `linkerOpts`. Every feature reaches
the catalog transitively (`feature → core:vault → core:db`), whose native driver binds system sqlite3
by cinterop, and those linker options do not propagate across module boundaries — so the flag lives
in the plugin, solved once, rather than being rediscovered per feature.

```kotlin
// feature/vault/build.gradle.kts — complete file
plugins { id("darkchamber.kmp.feature") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:vault"))
        }
    }
}
```

## 6. Version catalog

Single source of truth: `gradle/libs.versions.toml`; Renovate keeps it current; **Gradle dependency verification** (see [`RELEASE.md`](./RELEASE.md) §6) pins hashes.

Key entries (versions to be captured at project setup — verify latest stable):

```toml
[versions]
kotlin = "…"
composeMultiplatform = "…"
cryptographyKotlin = "…"     # pinned EXACTLY; pre-1.0 — wrapped by CryptoEngine
sqldelight = "…"
koin = "…"
decompose = "…"
coil = "…"                    # Coil 3 KMP, custom encrypted Fetcher
media3 = "…"                  # Android decrypting DataSource
kermit = "…"
turbine = "…"
detekt = "…"
konsist = "…"
kover = "…"
buildkonfig = "…"             # build flavors (store vs F-Droid), no secrets

[libraries]
cryptography-core    = { module = "dev.whyoleg.cryptography:cryptography-core", version.ref = "cryptographyKotlin" }
# + provider artifacts per target (JDK / Apple / OpenSSL)
# Argon2: platform binding behind `expect fun argon2id(...)` — no common artifact assumed
# … (kotlin-test, coroutines, sqldelight, koin, decompose, coil, media3, kermit, turbine)
```

Deliberately absent vs. a typical KMP app: Ktor, any backend SDK, any analytics/crash SDK. Their absence is enforced by the catalog being the only allowed dependency source (Konsist/dependency-review flags direct coordinates).
