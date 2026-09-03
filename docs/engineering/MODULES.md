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

- `core:model` — pure Kotlin domain types (`VaultItem`, `Album`, `Note`), MIME handling. Depends on nothing.
- `core:crypto` — the `.dchm` codec (normative spec: [`../FORMAT.md`](../FORMAT.md)), key hierarchy, KDF, DEK lifecycle, `CryptoEngine` interface wrapping `cryptography-kotlin`, `expect/actual SecureKeyStore` (Keystore/Secure Enclave). **The only module allowed to touch cipher primitives.**
- `core:storage` — app-private file layout, atomic writes, `tmp/` hygiene, thumbnail store. **The only module allowed to touch filesystem APIs.** *Built:* `VaultStorage(root: Path, fs = SystemFileSystem)` owns `objects/ thumbs/ keys/ tmp/` under an injected root, exposing:
    - object store — `writeObject`/`readObject`/`deleteObject`/`objectExists`, sharded, scoped atomic temp+`atomicMove`;
    - thumbnail store — `writeThumbnail`/… flat `.tdchm`;
    - key-blob store — `readKeyStore` (null-if-absent) / `writeKeyStore`;
    - tmp scratch — `useTempFile` (auto-delete) / `purgeTmp`.

    All ops return `Outcome`/`VaultError.Io` (no throws, nothing logged); depends on `core:model` + `kotlinx-io` only — **not `core:crypto`** (`.dchm` bytes flow through as opaque source/sink). Platform root-resolution + iOS `isExcludedFromBackup` live in the app module.
- `core:db` — SQLDelight schema (`.sq` files are the canonical DDL), drivers per platform, migrations. *Built:* schema v1 (`items`/`albums`/`audit_log`) generates `DarkChamberDatabase`; `CatalogStore(driver: SqlDriver)` is a thin `Outcome`-returning facade —
    - items — `upsertItem`/`getItem`/`listItems(albumId?)`/`deleteItem`/`countItems` + reactive `observeItems(albumId?): Flow`;
    - albums — `upsertAlbum`/`getAlbum`/`listAlbums`/`deleteAlbum`; audit — `appendAudit`/`listAudit(limit)`;
    - `create(driver)` runs schema create/migrate **fail-closed** → `VaultError.Db`; every op catches driver failures → `Db`, no throw crosses.

    Columns are opaque encrypted BLOBs (`enc_meta`/`dek_wrapped`/`enc_name`/`enc_payload`) + sanctioned plaintext index columns only; rows carry ids + opaque blobs (never decrypted content). Depends on `core:model` + SQLDelight only — **not `core:crypto`** (field encryption composes above). The `DriverFactory` `expect/actual` (Android `AndroidSqliteDriver` / iOS `NativeSqliteDriver`) is the production seam; `Context` + `<root>/db/catalog.db` path resolution lives in the app module. `verifyMigrations` guards the committed v1 dump.
- `core:vault` — the compose-the-cores layer: the one module that joins `core:crypto` (session/codec), `core:storage` (objects), and `core:db` (catalog rows) into vault-object operations. *Built:* `VaultObjects(session, storage, catalog, codec, clock, random)` —
    - `importObject` — stream-encrypts a source into a `.dchm` object atomically, mints a per-object DEK, seals a minimal `enc_meta` ({name,mime}) under MK, writes the `items` row (fail-closed: a row failure deletes the object);
    - `openObject` — streams the container back to a sink; `openMeta` — reads {name,mime} from the row without touching the object;
    - `shredObject` — crypto-shreds (delete the row/DEK first, best-effort delete the bytes).

    Everything is `Outcome`-typed and bounded-RAM (O(chunkSize)); depends on `core:model`+`crypto`+`storage`+`db` and imports **no cipher primitive** (`.dchm` bytes stay opaque, crypto stays behind `DchmCodec`/`VaultSession`). Random-access seek reads + the media viewers are Phase 1.
- `core:designsystem` — theme + tokens per [`../DESIGN.md`](../DESIGN.md), shared Compose components, secure-screen wrappers (`FLAG_SECURE` / snapshot obscuring live here so no feature re-implements them). *Built:* `DarkChamberTheme` (semantic color/type/shape/spacing/motion roles — consumers read roles, never raw hex — plus a Material 3 substrate mapped from the tokens) and the **`Aperture`** signature (the reserved full-circle unlock affordance, §7). Applied via the `darkchamber.kmp.compose` convention plugin. Still ahead: Plex fonts + the secure-screen wrappers.
- `core:logging` — Kermit setup (see [`PATTERNS.md`](./PATTERNS.md) §4 — no Crashlytics, no remote anything). *Built:* `initLogging()` (Kermit + platform writer only) and `redactCause(VaultError): String` / `logVaultError(stage, error)` — the hard-rule-#4 boundary that reduces any `VaultError` to a content-free code (`Io`/`Db`/`Crypto.*`), never the carried cause message, a path, an id, or vault content.
- `core:testing` — shared fakes, fixtures, test dispatchers, Turbine helpers, format test-vector loaders (test-only).

### App modules

- `:app` — the shared **composition root** (built). It owns:
    - the Koin graph — `commonAppModule` + `androidMain`/`iosMain` platform modules binding `VaultRootResolver`, `SecureKeyStore`, the `BiometricAuthenticator`, and the catalog driver;
    - the platform vault-root resolution — Android `filesDir` / iOS `Documents` + `isExcludedFromBackup`;
    - `VaultBootstrap` — the fail-closed cold-open (`createVault` / `unlock` / `unlockWithBiometric` / `enrollBiometric` / `probe`);
    - the session gate + first shared Compose UI — the Decompose `RootComponent` (`Locked | Unlocked`, `Unlocked` constructed with a live session), the `LockedComponent` state machine, the lock/create/recovery/unlock screens on `DarkChamberTheme`, the lock teardown + on-background lock, and the platform biometric authenticators (`BiometricPrompt` / `LAContext`);
    - the iOS surface — `iosMain` exports the **`Shared`** static umbrella framework (`export`s `core:model`/`core:crypto`/`core:db`) that `:iosApp` links, plus `MainViewController()` (the Compose gate host).

    Composes `core:crypto` **only via `CryptoEngine`/`SecureKeyStore`/`BiometricAuthenticator`** — no cipher primitive imported here — and adds no network dependency.
- `:androidApp` — thin launcher: `DarkChamberApp : Application` runs `initLogging()` + `startKoin { androidContext(this); modules(appModules()) }`; `MainActivity` is a **`FragmentActivity`** (required by `BiometricPrompt`) hosting the Decompose root via `retainedComponent` (session survives config changes, never persisted) + `RootContent`, and publishes itself to the `ActivityHolder` for the biometric prompt. Only the allowlisted `USE_BIOMETRIC` (+ legacy `USE_FINGERPRINT`) permission; no `INTERNET`.
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

Konsist runs as JUnit tests in `:konsist` and asserts what the compiler can't. Beyond the standard layer/naming rules (use cases in `..domain.usecase..`, ViewModels in `..presentation..`, DTOs confined to `..data..`), DarkChamber adds **security-invariant rules**:

```kotlin
class SecurityArchitectureTest {

    @Test
    fun `cipher primitives are only imported inside core-crypto`() {
        // dev.whyoleg.cryptography.*, javax.crypto.*, CommonCrypto interop, etc.
        Konsist.scopeFromProduction()
            .files
            .filterNot { it.moduleName == "core.crypto" }
            .assertFalse { file ->
                file.imports.any { it.name.startsWith("dev.whyoleg.cryptography") ||
                                   it.name.startsWith("javax.crypto") }
            }
    }

    @Test
    fun `shared-storage APIs are only touched inside core-storage`() {
        // MediaStore, Environment.getExternalStorage*, NSSearchPath outside sandbox, …
        Konsist.scopeFromProduction()
            .files
            .filterNot { it.moduleName == "core.storage" }
            .assertFalse { file ->
                file.imports.any { it.name.startsWith("android.provider.MediaStore") ||
                                   it.name.contains("ExternalStorage") }
            }
    }

    @Test
    fun `log calls never reference content fields`() {
        // heuristic guard for the PATTERNS.md §4 rule; reviewed, not sufficient alone
        // (tag-based check: forbid known content accessors in logging call arguments)
    }
}
```

These make the [`THREAT_MODEL.md`](../THREAT_MODEL.md) invariants self-policing: violating "all crypto goes through `CryptoEngine`" or "plaintext never touches shared storage" fails CI.

## 5. build-logic & convention plugins

An included `:build-logic` build centralizes KMP/Compose/quality setup into convention plugins, so per-module `build.gradle.kts` files stay tiny:

```
build-logic/src/main/kotlin/
├── darkchamber.kmp.library.gradle.kts      # KMP targets + common deps
├── darkchamber.kmp.feature.gradle.kts      # feature preset (compose, koin, viewmodel)
├── darkchamber.android.application.gradle.kts
├── darkchamber.compose.gradle.kts
├── darkchamber.quality.gradle.kts          # detekt + ktlint + kover, uniform
└── darkchamber.konsist.gradle.kts
```

```kotlin
// feature/notes/build.gradle.kts — complete file
plugins { id("darkchamber.kmp.feature") }

kotlin.sourceSets.commonMain.dependencies {
    implementation(projects.feature.vault.api)
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
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
