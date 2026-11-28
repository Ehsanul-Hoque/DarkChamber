# Guide for AI coding agents

## What this is

DarkChamber is a **Kotlin Multiplatform (KMP) + Compose Multiplatform (CMP)** app targeting Android and iOS: a **private camera + encrypted vault** for files (photos, videos, audio, PDFs, notes). Shared business logic **and** UI live in the `:core:*` / `:feature:*` modules; platform entry points are `:androidApp` (Kotlin) and `iosApp` (Xcode project, SwiftUI host).

Package root: `dev.ehsanfahad.darkchamber`.

> **Current state.** Phase 0 (the encrypted-vault core) is complete; **Phase 1 is in progress** — the
> gallery, import, albums and browsing, the private camera (photos and video), the viewer (images, video,
> audio, PDF, text and Markdown) and delete are built; export with metadata scrubbing is next. This file
> stays a map, not a mirror: `docs/` is the source of truth for *where things are going*, the **code** for
> *how things work*, and `docs/engineering/MODULES.md` describes each module in full.
>
> - **`:core:model`** — `VaultError` / `Outcome`.
> - **`:core:crypto`** — the `.dchm` codec behind the `CryptoEngine` seam, the key hierarchy, `VaultSession`, the
>   `SecureKeyStore` / `BiometricAuthenticator` seams, format test vectors. **The only module that touches cipher
>   primitives.**
> - **`:core:storage`** — `VaultStorage` over an injected root: objects, thumbnails, key blobs, purge-on-lock `tmp/`,
>   positioned reads. No `core:crypto` dependency.
> - **`:core:db`** — SQLDelight `CatalogStore`: opaque encrypted blobs plus plaintext index columns only. No
>   `core:crypto` dependency.
> - **`:core:vault`** — `VaultObjects`, the one place crypto, storage and db compose: streaming import/open/shred,
>   `openRange` (decrypts only the chunks a byte range touches), albums, live listings.
> - **`:core:designsystem`** — theme roles, the `Aperture` signature, icons as `ImageVector`s, and
>   `ExcludeFromPlatformStructures`: **every window the app opens — each dialog and menu included — must call it**
>   (a Konsist rule fails the build otherwise).
> - **`:core:logging`** — Kermit plus the `VaultError` redaction boundary (hard rule 4).
> - **`:feature:vault`** — gallery, import, albums, browse, the viewer and delete; reaches the vault only through
>   `core:vault`, navigates only through host callbacks; public surface = factories + the `api` package.
> - **`:feature:camera`** — the private camera behind the `CameraController` seam; photos memory-only, video through one
>   disclosed `tmp/` scratch file. Does not depend on `:feature:vault`.
> - **`:app`** — the composition root: Koin graph and every platform seam (pickers, camera, players, PDF renderers,
>   link openers, keystore, catalog driver), the fail-closed cold-open, the `Locked | Unlocked` session gate, unlock
>   and the lock lifecycle. Hosted by `:androidApp` (`FragmentActivity`) and `:iosApp` (`ComposeUIViewController`).
> - **`:konsist`** — architecture and **security-invariant** rules, run in CI on every PR.
> - **`:core:testing`** — test support: the Compose UI-test helpers (`UiTest`, themed content, the shared
>   finders); a module opts in with `darkchamber.compose.uitest` and writes its UI tests once in `commonTest`.
>
> **Tooling.** Wired: a tracked `commit-msg` hook (`./gradlew installGitHooks`) running CI's commit check (scopes in
> `config/commit-scopes.txt`); a tracked `pre-commit` hook refusing a doc comment cut off its declaration
> (`scripts/check-doc-comments.sh`, also in CI) and, when Kotlin is staged, running `spotlessCheck detekt`
> (`scripts/check-formatting.sh` — it refuses a staged Kotlin file with unstaged changes); `pr.yml` (build, commit check, a gated macOS job running the iOS
> tests, and the `quality` job: workflow-file validation, Spotless with ktlint, detekt, Kover's per-module coverage
> floors); shared Compose UI tests on the Android host (Robolectric) and the iOS simulator. **Not wired yet:**
> `migrationTest`; `nightly` / `release` / `test-build` workflows stay `*.yml.disabled` — verify a Gradle task exists
> before assuming it runs.

## Source of truth — read these first

- `ROADMAP.md` — phased plan (vault+camera → utilities → paid cloud backup → suite), monetization/licensing, **binding non-goals**.
- `docs/ARCHITECTURE.md` — modules, storage layout, crypto integration, camera/viewer/session design.
- `docs/THREAT_MODEL.md` — in/out-of-scope threats and **security invariants**. Normative: no code or copy may promise more than this file.
- `docs/FORMAT.md` — normative `.dchm` container spec (chunked AES-256-GCM), key hierarchy, versioning policy, test vectors.
- `docs/DESIGN.md` — design tokens (color/type/shape/motion), the aperture signature, honesty rules for UI copy. Feature UI consumes `DarkChamberTheme` roles from `core:designsystem` — never raw hex, and the full-circle radius is reserved for aperture elements.
- `docs/engineering/` — MODULES, PATTERNS, TESTING, WORKFLOW, RELEASE. **Read before contributing code.**
- `docs/ADR/` — decisions incl. the refusals (ADR-001 no auto-encryption of other apps' photos; ADR-002 no app lock; ADR-003 no sandboxing; ADR-004 **no INTERNET permission in v1**).

## Hard security rules — never violate, never "helpfully" work around

These are the project's load-bearing constraints. If a task seems to require breaking one, stop and flag it instead of implementing.

1. **Plaintext never touches shared storage.** Decrypted data exists only in memory or app-private `tmp/` (wiped on lock). No MediaStore writes, no external cache, no plaintext thumbnails. Violation = P0 regardless of observed impact.
2. **All cryptography goes through `core:crypto` / `CryptoEngine`.** Never import cipher primitives (`dev.whyoleg.cryptography.*`, `javax.crypto.*`, CommonCrypto) elsewhere; never invent crypto constructions — the `.dchm` spec in `docs/FORMAT.md` is the only sanctioned format, and changes to it require a version bump + migration plan ("old vaults always open").
3. **No `INTERNET` permission, no network code, no `core:network` in v1** (ADR-004). Do not add networking dependencies, analytics, crash SDKs, or "phone home" of any kind. The release pipeline hard-fails on the permission.
4. **Logs never contain content** — no file names, note titles/bodies, album names, key material, passphrases, or plaintext derived from vault items. Identifiers, sizes, event names, error codes only. No Crashlytics or remote logging ever; domain-model `toString()` is redacted.
5. **Key material hygiene.** Raw MK/DEKs live only in the in-memory `VaultSession`; zeroize on lock; never persist, never log, never pass through platform IPC.
6. **Honesty in UX copy.** The shredder, iOS screenshot behavior, and import-original deletion all carry documented limitations — do not write UI text, docs, or store copy that promises more than `docs/THREAT_MODEL.md` allows.
7. **Permissions are allowlisted** (`config/permissions-allowlist.txt`). Adding any permission is a security-review change, not a convenience.

## Commands

Build:
```bash
./gradlew :androidApp:assembleFossDebug    # Android debug (foss flavor)
./gradlew :androidApp:assembleFossRelease  # signed release APK (version from git tag)
./gradlew :androidApp:printVersion         # print resolved versionName / versionCode
# iOS: open iosApp/ in Xcode and run
```
Flavors: `foss` (F-Droid/GitHub; default for releases) and `store` (Play; identical in v1, gains Play Billing in Phase 3). Task names include the flavor: `assembleFossDebug`, `assembleStoreRelease`.

Test:
```bash
./gradlew testAndroidHostTest              # JVM/Android unit tests across modules (fast, run on every change)
./gradlew iosSimulatorArm64Test            # iOS tests (expensive — CI-gated to core/feature changes)
```

Architecture + security rules (**exists today**, and runs in CI on every PR):
```bash
./gradlew :konsist:test       # architecture + SECURITY-INVARIANT rules (crypto/storage confinement)
```
Konsist's rules are ordinary tests in their own module, so they run as that module's test task — there is
no top-level `konsistTest`.

Quality & coverage (the `darkchamber.quality` convention plugin; all run in CI's `quality` job):
```bash
./gradlew spotlessApply       # auto-fix formatting (ktlint + the Compose rules) — run before committing
./gradlew spotlessCheck       # fails on any formatting difference or ktlint finding
./gradlew detekt              # static analysis; per-module baselines, none in core:crypto
./gradlew koverVerify         # per-module line-coverage floors, set from measurement (docs/engineering/TESTING.md §4)
./gradlew migrationTest       # fixture-vault upgrade gate — does NOT exist yet (arrives after v1.0 ships)
```
The coverage targets (core:crypto ~100%, domain 90%, overall 80%) are where the floors are headed; the floors are
what is enforced. Compose UI tests live in `commonTest`, extend `:core:testing`'s `UiTest`, and find text through
the screen's own format functions — see `docs/engineering/TESTING.md` §8.

Versions are centralized in `gradle/libs.versions.toml`; never hardcode versions in a `build.gradle.kts`. `cryptography-kotlin` is pre-1.0 and pinned **exactly** — do not bump it casually; it sits behind our `CryptoEngine` interface.

## Architecture — the non-obvious parts

**`.dchm` container (docs/FORMAT.md).** Every encrypted object — file, note body, thumbnail — is one chunked, authenticated format: a **16-byte plaintext prelude** (`DCHM` magic, version, cipher_id, header_len, per-object random `nonce_prefix`) bound as the header's AAD; encrypted CBOR header sealed under the reserved header nonce `nonce_prefix ‖ 0xFFFFFFFF`; then 1 MiB chunks of AES-256-GCM with `nonce = nonce_prefix ‖ u32 chunk counter` and AAD binding `file_id ‖ chunk_index ‖ is_last_flag` (defeats reordering/truncation/splicing). Chunking is what enables streaming, video **seeking** (decrypt chunk ⌊offset/CS⌋), and resumable backup — never replace it with whole-file encryption.

**Envelope key hierarchy.** Random 256-bit MasterKey, wrapped three ways: hardware keystore (biometric/PIN-gated), Argon2id(passphrase), recovery key. MK wraps per-object DEKs. Consequences to preserve: password change re-wraps MK only (no data re-encryption); deleting a DEK **is** deletion (crypto-shredding); panic wipe = destroy MK wraps; recovery key is the only path on a fresh device.

**The session gate is structural, not per-screen.** The Decompose root has exactly two children: `LockedComponent` and `UnlockedComponent`; the latter is *constructed with* a non-null `VaultSession`. Locking replaces the whole Unlocked subtree — screens cannot exist without a session by construction. Never add a decrypted-content screen outside that subtree, and keep MVI `State` classes holding item **ids**, not decrypted bytes.

**Viewers decrypt to memory.** Images: decode from decrypted byte arrays (Coil custom Fetcher, memory-cache only). Video/audio: Media3 custom `DataSource` / `AVAssetResourceLoaderDelegate` mapping byte ranges to chunks. PDFs: decrypted whole into memory and rendered only after the read verifies — Android an anonymous memory file (`memfd`, 11+; older versions don't show PDFs rather than write a file), iOS in-memory data through CoreGraphics; never a `tmp/` file or a cache. Text: decrypted whole into an array of the stored size, decoded after the read verifies, the array zeroed; Markdown drawn by our own code from the parser's tree — never a web view or a platform HTML engine, and a link leaves only through the warning dialog and the `LinkOpener` seam, which locks the vault once the browser has the address. Other types: explicit "decrypt & open in…" with a leaves-the-vault warning + cleanup.

**Two honest shredder modes.** Vault items → crypto-shred (destroy DEK; instant, irreversible). External files → single-pass best-effort overwrite **with a non-dismissable disclosure** about flash wear-leveling. Do not add multi-pass "DoD" modes — the project explicitly rejects wipe theater.

**Target module structure.** Multi-module with compile-time isolation: features split `api`/`impl` (only the app module sees `:impl`); `core:model` / `core:crypto` / `core:storage` / `core:db` / `core:designsystem` / `core:logging` / `core:testing`; `presentation → domain ← data` inside features; Konsist enforces layers **plus** the security confinement rules. Convention plugins (`darkchamber.*`) in `build-logic` configure everything; per-module build files stay tiny.

**DI (Koin).** Each feature `impl` owns its Koin module; the **app module is the composition root**; no `core:di` aggregator.

**Error handling.** Typed results (`VaultError` sealed interface), no exceptions across layers. Two rules with security weight: `Crypto.IntegrityFailure` (GCM tag mismatch) is never silently retried or partially rendered — surface it as possible tampering; `UnsupportedFormatVersion` must produce the "update the app" path, never a crash.

## Build, release & environments

**Version resolution.** `androidApp/build.gradle.kts` derives the version — never hardcoded. Priority: CI's `-PversionName`/`-PversionCode` (from `scripts/compute-version.sh`) → nearest git tag → `0.0.0`. `versionCode = major*100000 + minor*1000 + patch`. Check with `:androidApp:printVersion`. R8 is off with a TODO (keep rules needed for Compose/Koin/SQLDelight/serialization/cryptography-kotlin; must stay deterministic for reproducible builds).

**Signing.** Conditional on `local.properties` (absent on CI): optional shared dev keystore for debug; local release falls back dev→prod; CI injects signing via `-Pandroid.injected.signing.*` from `scripts/build-release-apk.sh`. **No keys in the repo**; no service requires SHA registration (there are no services).

**Release pipeline (manual, tag-based, batched).** Merging to `main` never releases. `scripts/compute-version.sh` (one batched bump, highest level wins, pre-1.0 breaking→minor) → release checklist (tests incl. format vectors; **apkanalyzer permission audit: hard-fail on INTERNET, allowlist for the rest**; human threat-model confirmation input) → `build-release-apk.sh` (FLAVOR=foss default; APK named `darkchamber-<flavor>-<version>.apk`) → `tag-and-release.sh` (tags `main` HEAD, GitHub Release; no commit pushed to `main`). Test builds: `publish-test-build.sh` → GitHub **pre-release** with throwaway `0.0.0-<branch>-<sha>` version (no third-party distribution). Workflows live as `*.yml.disabled` until quality tasks exist; don't mark them required in branch protection before they're green.

**There are no dev/prod cloud environments** — no backend exists. Phase 3 adds the user's own Drive/iCloud only.

## Conventions

- **TDD is mandatory** — tests before implementation; the format test vectors (incl. negative vectors) and, post-v1, the fixture-vault migration gate are release-blocking.
- **Conventional Commits, per-commit** (we merge, not squash): `feat:` (minor), `fix:`/`perf:` (patch), `feat!:`/`BREAKING CHANGE:` (major → minor while 0.x), `refactor:`/`docs:`/`test:`/`chore:`/`ci:`/`build:`/`style:` (no bump). It's `feat`/`docs`, **not** `feature`/`doc`. CI re-validates via `scripts/check-commits.sh` (no Node/commitlint).
- **Tag-based versioning** — read the current version from tags/Releases or `git describe`, never from a file in the repo.
- **`main` is protected** — reviewed PRs only. PRs touching `core:crypto`, `core:storage`, the format, key lifecycle, or session/lock logic carry the `security-review` label and state which threat-model invariants they touch.
- **Never commit as an agent** — output a suggested Conventional-Commit message for the maintainer to run manually.
- **Ask questions in plain chat — never use `AskUserQuestion` or similar interactive pickers.** Those widgets have terminal UI bugs: they truncate long answers, recommendation tags, and suggested code blocks, and once an option is chosen they hide the other options so the decision can't be reviewed later. Instead, write the question and its options as normal chat text so nothing is cut off and the full history survives. For each question: list every option, tag the recommended one and explain **why** it's recommended, and include any relevant code/preview inline. This keeps the question, the chosen answer, and all alternatives visible in scrollback so the maintainer can revisit a decision and ask to change it.
- **Dependency discipline** — new dependencies only via the version catalog, justified in the PR description, GPLv3-compatible; Gradle dependency verification will reject unpinned artifacts once `gradle/verification-metadata.xml` lands.
