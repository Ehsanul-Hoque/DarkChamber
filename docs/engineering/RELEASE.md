# CI/CD, Versioning & Releases

> Part of the [Engineering Guide](./README.md). Workflows, tag-based versioning, scripts, secrets, supply chain.

## 1. Workflows overview

| Workflow         | Trigger                                    | Purpose                                                                                                             |
|------------------|--------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `pr.yml`         | every PR to `main`                         | quality gates: lint, architecture, tests, format vectors, coverage, commit check, dependency review                 |
| `release.yml`    | manual `workflow_dispatch`                 | compute version from commits since last tag → build signed APK → tag + GitHub Release                               |
| `test-build.yml` | manual `workflow_dispatch` from any branch | throwaway-versioned APK published as a GitHub **pre-release** for testers                                           |

## 2. PR validation — `pr.yml`

Fast checks on Linux; the expensive macOS job runs only when shared/iOS code changed (path filter via `dorny/paths-filter` setting a job-gating output). The workflow file is the source of truth, its comments the reasons; the jobs, in short:

| Job                 | Runs                                                                                                        |
|---------------------|-------------------------------------------------------------------------------------------------------------|
| `changes`           | the path filter that decides whether `ios-tests` runs                                                       |
| `commit-messages`   | the commit checker's fixtures and the check over the PR; the pre-commit formatting check's fixtures          |
| `dependency-review` | the supply-chain gate on new dependencies (public repositories only)                                        |
| `build`             | `:konsist:test` first (the security-invariant rules), then `assembleFossDebug` and the host suite            |
| `quality`           | the workflow files validated, then `spotlessCheck detekt`, then `koverVerify` with each module's report kept |
| `ios-tests`         | the iOS suite on a simulator, and the device-architecture compile — non-draft PRs that touch shared code     |

`migrationTest` joins the iOS job as a step once the first release has shipped a fixture vault
([`TESTING.md`](./TESTING.md) §3.2).

## 3. Versioning — tag-based SemVer

- Plain **SemVer** `MAJOR.MINOR.PATCH`; width convention major/minor ≤ 99, patch ≤ 999.
- `versionCode = major*100000 + minor*1000 + patch` (monotonic, Play-safe).
- **The version is derived from the latest Git tag** — nothing on `main` stores it, so CI never commits to `main` and branch protection needs no bypass. Read it with `git describe --tags --abbrev=0`; current-version-of-record is the Releases page.
- Pre-1.0: a breaking change bumps **minor** (SemVer guarantees start at 1.0.0).
- Bump computation batches everything since the last tag into **one** bump — the **highest** level wins (five `feat:`s → a single minor).

Implemented in [`scripts/compute-version.sh`](../../scripts/compute-version.sh), which also writes grouped release notes (`build/release-notes.md`) and a Play "what's new" draft (`build/whatsnew-en-US.txt`) for later store submissions.

## 4. Release — `release.yml`

Manual `workflow_dispatch` with two inputs: `level` (`auto` | `patch` | `minor` | `major`) and `dry_run` (preview version + changelog, change nothing).

```
merge PRs freely → main accumulates commits (no releases)
        │  (you click "Run workflow")
        ▼
compute-version.sh ── level=none → stop (nothing release-worthy)
        ▼
[release checklist job — see §5]
        ▼
build-release-apk.sh   (signed, VERSION_NAME/CODE injected as Gradle props)
        ▼
tag-and-release.sh     (annotated tag vX.Y.Z on main's HEAD + GitHub Release
                        with changelog body, APK + whatsnew as assets)
```

Scripts: [`build-release-apk.sh`](../../scripts/build-release-apk.sh) · [`tag-and-release.sh`](../../scripts/tag-and-release.sh). Store (Play/App Store) submission stays manual for now; F-Droid builds from tags via its own recipe once listed.

## 5. Release checklist — automated where possible

The release job runs, before tagging (failing any step aborts the release):

1. Full test suite including **format vectors** and the **migration gate** ("old vaults always open").
2. `apkanalyzer` assertions on the built APK: **no `INTERNET` permission (v1, ADR-004)**, no unexpected permissions vs. a committed allowlist (`config/permissions-allowlist.txt`).
3. Dependency verification strict mode (§6).
4. A human-confirmed input (`threat_model_reviewed: true`) — the one step that stays deliberately manual: confirm the release introduces no undocumented threat-model change.

## 6. Supply chain

- **Gradle dependency verification** (`gradle/verification-metadata.xml`): checksums (and PGP where published) for every dependency and plugin; `--verify-metadata` in CI. An unpinned or altered artifact fails the build.
- **`actions/dependency-review-action`** flags newly introduced dependencies/licenses on PRs; new runtime dependencies require justification in the PR description (GPLv3 compatibility included).
- Renovate updates the catalog **and** verification metadata via PRs — updates land only through review, never silently.
- GitHub Actions pinned by SHA for anything with secret access.

## 7. Reproducible builds (v1.x goal → F-Droid)

- Pinned JDK/toolchains via Gradle toolchain support; deterministic flags (fixed timestamps, stable resource ordering); no build-time network beyond verified dependency resolution.
- Goal state: `SOURCE_DATE_EPOCH`-style determinism such that F-Droid's builder output matches our APK bit-for-bit (minus signature), enabling reproducible-signing publication.
- Verification instructions (`apksigner verify --print-certs`, expected cert digest) published in the README once the first store build ships; a binary/source mismatch is a critical incident per [`SECURITY.md`](../../SECURITY.md).

## 8. Test builds for testers — `test-build.yml`

Replaces third-party distribution services: builds a signed APK from any branch with a throwaway version (`0.0.0-<branch>-<shortsha>`, versionCode 1) and publishes it as a GitHub **pre-release** (marked `--prerelease`, title `Test build: <branch>@<sha>`). Testers install straight from the Releases page — no accounts, no Play Services required, works for F-Droid-style users. Implemented in [`scripts/publish-test-build.sh`](../../scripts/publish-test-build.sh). Pre-releases are pruned periodically (they are not supported versions per `SECURITY.md`).

## 9. Secrets & config

- **CI secrets:** `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASS` (binary secrets stored base64). That's the entire v1 list — no service accounts, no API keys, because there are no services.
- The release keystore is decoded to a temp dir and shredded via `trap` cleanup ([`build-release-apk.sh`](../../scripts/build-release-apk.sh)); it never touches the workspace.
- **BuildKonfig** injects build *configuration* (flavor: `store` vs `foss`, feature gates) — never secrets; there are no secrets in the app by design.
- `local.properties`, `*.keystore` git-ignored; keystore backed up offline (losing it = losing the Play listing identity).
