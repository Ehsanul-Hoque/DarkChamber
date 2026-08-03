# Guide for AI coding agents

## What this is

DarkChamber is a **Kotlin Multiplatform (KMP) + Compose Multiplatform (CMP)** app targeting Android and iOS: a **private camera + encrypted vault** for files (photos, videos, audio, PDFs, notes). Shared business logic **and** UI live in `:shared`; platform entry points are `:androidApp` (Kotlin) and `iosApp` (Xcode project, SwiftUI host).

Package root: `dev.ehsanfahad.darkchamber`.

> **Current state vs. target.** The repo is early-stage scaffolding: `:shared` holds template sample code, and only `:androidApp` + `:shared` modules exist. The docs in `docs/` describe the **target** design (multi-module clean architecture, `.dchm` crypto core, full tooling). Much of the tooling referenced below (build-logic convention plugins, ktlint/detekt/Konsist/Kover tasks, Lefthook, `migrationTest`) is **planned, not yet wired up** — verify a Gradle task exists before assuming it runs. Release scripts (`scripts/`) and CI workflows exist, but workflows are committed **disabled** (`*.yml.disabled`) until the quality tasks land. Treat `docs/` as the source of truth for *where things are going*.

## Source of truth — read these first

- `ROADMAP.md` — phased plan (vault+camera → utilities → paid cloud backup → suite), monetization/licensing, **binding non-goals**.
- `docs/ARCHITECTURE.md` — modules, storage layout, crypto integration, camera/viewer/session design.
- `docs/THREAT_MODEL.md` — in/out-of-scope threats and **security invariants**. Normative: no code or copy may promise more than this file.
- `docs/FORMAT.md` — normative `.dchm` container spec (chunked AES-256-GCM), key hierarchy, versioning policy, test vectors.
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
./gradlew :shared:testAndroidHostTest      # JVM/Android unit tests (fast, run on every change)
./gradlew :shared:iosSimulatorArm64Test    # iOS tests (expensive — CI-gated to shared changes)
```

Quality & coverage (target tooling — confirm the task exists before relying on it):
```bash
./gradlew ktlintFormat        # auto-fix formatting — run before committing
./gradlew ktlintCheck detekt  # lint + static analysis
./gradlew konsistTest         # architecture + SECURITY-INVARIANT rules (crypto/storage confinement)
./gradlew koverVerify         # coverage gates: core:crypto ~100%, domain 90%, overall 80%
./gradlew migrationTest       # fixture-vault upgrade gate (exists after v1.0 ships)
```

Versions are centralized in `gradle/libs.versions.toml`; never hardcode versions in a `build.gradle.kts`. `cryptography-kotlin` is pre-1.0 and pinned **exactly** — do not bump it casually; it sits behind our `CryptoEngine` interface.

## Architecture — the non-obvious parts

**`.dchm` container (docs/FORMAT.md).** Every encrypted object — file, note body, thumbnail — is one chunked, authenticated format: plaintext prelude (`DCHM` magic, version, cipher_id) bound as AAD; encrypted CBOR header; then 1 MiB chunks of AES-256-GCM with `nonce = per-file random prefix ‖ chunk counter` and AAD binding `file_id ‖ chunk_index ‖ is_last_flag` (defeats reordering/truncation/splicing). Chunking is what enables streaming, video **seeking** (decrypt chunk ⌊offset/CS⌋), and resumable backup — never replace it with whole-file encryption.

**Envelope key hierarchy.** Random 256-bit MasterKey, wrapped three ways: hardware keystore (biometric/PIN-gated), Argon2id(passphrase), recovery key. MK wraps per-object DEKs. Consequences to preserve: password change re-wraps MK only (no data re-encryption); deleting a DEK **is** deletion (crypto-shredding); panic wipe = destroy MK wraps; recovery key is the only path on a fresh device.

**The session gate is structural, not per-screen.** The Decompose root has exactly two children: `LockedComponent` and `UnlockedComponent`; the latter is *constructed with* a non-null `VaultSession`. Locking replaces the whole Unlocked subtree — screens cannot exist without a session by construction. Never add a decrypted-content screen outside that subtree, and keep MVI `State` classes holding item **ids**, not decrypted bytes.

**Viewers decrypt to memory.** Images: decode from decrypted byte arrays (Coil custom Fetcher, memory-cache only). Video/audio: Media3 custom `DataSource` / `AVAssetResourceLoaderDelegate` mapping byte ranges to chunks. PDFs: iOS in-memory `PDFDocument(data:)`; Android v1 uses a private-`tmp/` file deleted on close (documented tradeoff — don't "improve" it into shared cache). Other types: explicit "decrypt & open in…" with a leaves-the-vault warning + cleanup.

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
- **Dependency discipline** — new dependencies only via the version catalog, justified in the PR description, GPLv3-compatible; Gradle dependency verification will reject unpinned artifacts once `gradle/verification-metadata.xml` lands.
