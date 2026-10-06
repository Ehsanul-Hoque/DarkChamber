# Guide for AI coding agents

## What this is

DarkChamber is a **Kotlin Multiplatform (KMP) + Compose Multiplatform (CMP)** app targeting Android and iOS: a **private camera + encrypted vault** for files (photos, videos, audio, PDFs, notes). Shared business logic **and** UI live in the `:core:*` / `:feature:*` modules; platform entry points are `:androidApp` (Kotlin) and `iosApp` (Xcode project, SwiftUI host).

Package root: `dev.ehsanfahad.darkchamber`.

> **Current state.** Phase 0 is **complete** — the encrypted-vault core is built — and **Phase 1 is
> in progress**: the vault feature module ships the gallery and import. The module graph is
> wired by the `:build-logic` convention plugins (`darkchamber.kmp.library`,
> `darkchamber.android.application`, `darkchamber.kmp.compose`, `darkchamber.kmp.feature`), versions
> pinned in `gradle/libs.versions.toml`. What exists:
>
> - **`:core:model`** — `VaultError` / `Outcome` types.
> - **`:core:crypto`** — the `.dchm` container codec (chunked AES-256-GCM) behind the swappable
>   `CryptoEngine` seam; the key hierarchy (MasterKey/DEK, a CBOR `KeyStore` with an Argon2id
>   passphrase slot + PBKDF2 fallback + HKDF recovery slot, Crockford-base32 recovery key); the
>   in-memory `VaultSession`; and the injected `SecureKeyStore` + `BiometricAuthenticator` seams.
>   Committed format + KeyStore test vectors. **The only module that touches cipher primitives.**
> - **`:core:storage`** — `VaultStorage` over an injected root: atomic object/thumbnail/key-blob
>   stores + purge-on-lock `tmp/`. No `core:crypto` dep (`.dchm` bytes flow through opaque).
> - **`:core:db`** — SQLDelight schema v1 (`items`/`albums`/`audit_log`) + a fail-closed `CatalogStore`
>   over an injected driver. Opaque encrypted-BLOB + plaintext-index columns only; no `core:crypto` dep.
> - **`:core:vault`** — `VaultObjects`: streaming `importObject`/`openObject`/`openMeta`/`shredObject`;
>   the one place crypto + storage + db compose. Imports no cipher primitive.
> - **`:core:designsystem`** — `DarkChamberTheme` roles + the `Aperture` signature.
> - **`:core:logging`** — Kermit + the `VaultError` redaction boundary (hard rule #4).
> - **`:app`** — the composition root: the Koin graph, the platform seams (`VaultRootResolver`, the
>   catalog driver, `SecureKeyStore`), `VaultBootstrap`'s fail-closed cold-open, the Decompose
>   `Locked | Unlocked` session gate (Unlocked constructed with a live session), cryptographic per-use
>   biometric unlock (passphrase fallback + forced-passphrase on re-enrollment), the create/recovery/
>   unlock Compose UI, and the lock lifecycle (teardown on Lock **and** on background). Hosted by an
>   Android `FragmentActivity` (`:androidApp`) and an iOS `ComposeUIViewController` linking the
>   `Shared` umbrella framework (`:iosApp`).
> - **`:feature:vault`** — the gallery (adaptive grid over `observeItems`, newest first, encrypted
>   previews in the media slot), import (multi-select file + photo pickers behind `FilePicker` /
>   `PhotoPicker` seams the app implements, a sequential batch with two-level progress,
>   continue-on-error and a stop that lands **within** a file, thumbnails generated inline at import
>   under their own DEK, and the original-delete offer behind its non-dismissable disclosure) and the
>   **album surface**: one item grid serving three listings (whole vault / one album / unfiled), the
>   album grid, create/rename/delete, a multi-select that moves items in and out, and imports landing in
>   the album being viewed. An album is a **label, not a container** — deleting one keeps its items
>   (they become unfiled) and an item is in one album at a time, so filing is a move; see
>   `docs/ARCHITECTURE.md` §5.1. Public surface is two factories plus the `api` package; everything else
>   is `internal`. Reaches the vault through `core:vault` only, and navigates only through callbacks the
>   host supplies.
> - **`:konsist`** — the architecture + **security-invariant** rules, running in CI on every PR.
>
> Still a stub (marker + smoke test): `:core:testing`. **In progress: Phase 1** — albums are in; next
> are sort/search over the listing, the camera, the seek primitive and the in-app viewers (deferred
> from Phase 0). **Planned, not yet wired:** ktlint/detekt/Kover, Lefthook and
> `migrationTest` — verify a Gradle task exists before assuming it runs. CI:
> `pr.yml` is enabled (build + commit-message check + gated iOS compile); its `quality` job stays
> commented out until those plugins land, and `nightly`/`release`/`test-build` stay committed
> **disabled** (`*.yml.disabled`). Treat `docs/` as the source of truth for *where things are going*,
> and the **code** as the source of truth for *how things work* (this file stays a map, not a mirror).

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
The `:konsist` module has no quality convention plugin applied, so its rules run as that module's own
test task — there is no top-level `konsistTest`.

Quality & coverage (target tooling — **none of these tasks exist yet**; confirm before relying on one):
```bash
./gradlew ktlintFormat        # auto-fix formatting — run before committing
./gradlew ktlintCheck detekt  # lint + static analysis
./gradlew koverVerify         # coverage gates: core:crypto ~100%, domain 90%, overall 80%
./gradlew migrationTest       # fixture-vault upgrade gate (exists after v1.0 ships)
```

Versions are centralized in `gradle/libs.versions.toml`; never hardcode versions in a `build.gradle.kts`. `cryptography-kotlin` is pre-1.0 and pinned **exactly** — do not bump it casually; it sits behind our `CryptoEngine` interface.

## Architecture — the non-obvious parts

**`.dchm` container (docs/FORMAT.md).** Every encrypted object — file, note body, thumbnail — is one chunked, authenticated format: a **16-byte plaintext prelude** (`DCHM` magic, version, cipher_id, header_len, per-object random `nonce_prefix`) bound as the header's AAD; encrypted CBOR header sealed under the reserved header nonce `nonce_prefix ‖ 0xFFFFFFFF`; then 1 MiB chunks of AES-256-GCM with `nonce = nonce_prefix ‖ u32 chunk counter` and AAD binding `file_id ‖ chunk_index ‖ is_last_flag` (defeats reordering/truncation/splicing). Chunking is what enables streaming, video **seeking** (decrypt chunk ⌊offset/CS⌋), and resumable backup — never replace it with whole-file encryption.

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
- **Ask questions in plain chat — never use `AskUserQuestion` or similar interactive pickers.** Those widgets have terminal UI bugs: they truncate long answers, recommendation tags, and suggested code blocks, and once an option is chosen they hide the other options so the decision can't be reviewed later. Instead, write the question and its options as normal chat text so nothing is cut off and the full history survives. For each question: list every option, tag the recommended one and explain **why** it's recommended, and include any relevant code/preview inline. This keeps the question, the chosen answer, and all alternatives visible in scrollback so the maintainer can revisit a decision and ask to change it.
- **Dependency discipline** — new dependencies only via the version catalog, justified in the PR description, GPLv3-compatible; Gradle dependency verification will reject unpinned artifacts once `gradle/verification-metadata.xml` lands.
