# Testing & Code Quality

> Part of the [Engineering Guide](./README.md). Tests are written before implementation; coverage and architecture rules gate CI.

## 1. Tooling

| Tool                        | Used for                                                                    |
|-----------------------------|-----------------------------------------------------------------------------|
| `kotlin-test`               | assertions, common test entry points                                        |
| `kotlinx-coroutines-test`   | `runTest`, virtual time, test dispatchers                                   |
| **Turbine**                 | asserting `StateFlow`/`Flow` emission sequences (MVI)                       |
| in-memory SQLDelight driver | repository/local tests without a device                                     |
| **Robolectric**             | Android-framework unit tests on the JVM                                     |
| `koin-test`                 | verifying module graphs resolve                                             |
| **Konsist**                 | architecture + security-invariant rules ([`MODULES.md`](./MODULES.md) §4.2) |
| **Roborazzi** (optional)    | screenshot tests for secure-screen states                                   |

## 2. What to test at each layer

- **`core:crypto`** — the crown jewels; see §3. Pure JVM tests, no mocks of the crypto itself.
- **domain** — use cases in pure JVM tests with fake repositories.
- **data** — repositories against in-memory SQLDelight + a temp-dir `core:storage`; assert mapping and that plaintext never lands outside the sandbox (the fake storage asserts path prefixes).
- **presentation** — ViewModels with Turbine: feed intents, assert exact `State`/`Effect` sequences, including the lock-mid-operation path (session death must surface `AuthRequired`, never a crash or a stale decrypted state).

## 3. Security-critical gates (the RLS-equivalent)

The highest-value tests in DarkChamber guard the two things that can silently destroy user trust: **the format** and **migrations**. Both are mandatory CI gates — a release cannot be cut if either fails.

### 3.1 Format test vectors ([`FORMAT.md`](../FORMAT.md) §7)

`docs/format-test-vectors/` holds, per cipher_id: fixed keys/nonces/plaintexts with expected ciphertext for single-chunk, multi-chunk, and empty files, **plus negative vectors that MUST fail** — bad tag, reordered chunks, truncated stream, wrong `is_last_flag`, altered plaintext prelude. `core:testing` ships the loader; the suite runs on every commit.

**Enforce completeness, don't rely on memory:** a meta-test enumerates all shipped format versions and cipher_ids (read from one constant in `core:crypto`) and asserts each has a vector set — adding a cipher without vectors fails the suite, exactly the pattern that kept RLS policies honest in the sibling project.

### 3.2 Migration gate

CI runs an upgrade test **from every previously released schema and format version**: fixture vaults (tiny, committed per release) are opened by the current build; every item must decrypt and every catalog row survive. A failed migration must fail *closed* in production code (refuse to run, vault untouched). "Old vaults always open" is a release-blocking invariant, per ARCHITECTURE.md §14.

### 3.3 Property/round-trip fuzzing

Randomized round-trips (encrypt → decrypt == identity across sizes 0..multi-chunk; random truncations/bit-flips must fail authentication) run in the PR suite with a bounded iteration count and nightly with a larger one.

## 4. Coverage policy — Kover

| Scope                 | Minimum                                                       |
|-----------------------|---------------------------------------------------------------|
| `core:crypto`         | **95** (target 100; every uncovered line justified in the PR) |
| domain (all features) | 90                                                            |
| data / presentation   | 80                                                            |
| overall gate          | 80                                                            |

Excluded: `*.di.*`, generated code, Compose previews. CI posts the Kover report as a PR comment.

## 5. Quality tools

Applied uniformly via the `darkchamber.quality` convention plugin; configs live at repo root.

| Tool        | Config                     | Enforces                                | Task                           |
|-------------|----------------------------|-----------------------------------------|--------------------------------|
| **ktlint**  | `.editorconfig`            | formatting (auto-fixable)               | `ktlintCheck` / `ktlintFormat` |
| **detekt**  | `config/detekt/detekt.yml` | static analysis, complexity, Koin rules | `detekt`                       |
| **Konsist** | `:konsist` module          | architecture + security invariants      | `:konsist:test`                |
| **Kover**   | quality plugin             | coverage thresholds (§4)                | `koverVerify`                  |

**Konsist is the exception on both counts, and the only one of the four that exists today.** It is not
applied by the quality plugin: its rules are ordinary tests in their own module, so they run as that
module's test task and there is no aggregate `konsistTest` to invoke. Run it on its own rather than
alongside the others, since a single command naming a task that does not exist yet fails before
reaching the one that does.

detekt version note: pick the line built against the project's Kotlin version at setup (this pairing is the most volatile in the toolchain); a baseline file may grandfather issues on adoption but **never** in `core:crypto`.

## 6. Running

```bash
./gradlew :konsist:test                      # architecture + security invariants
./gradlew ktlintCheck detekt                 # style
./gradlew testDebugUnitTest                  # fast JVM suite (incl. format vectors)
./gradlew iosSimulatorArm64Test              # iOS, on a simulator; runs on pull requests that touch
                                             # shared code (gated to non-draft, see RELEASE.md). Worth
                                             # running locally before any iOS-visible change: it is the
                                             # only suite that exercises the native SQLite driver, the
                                             # iOS platform seams, and the container vectors on a
                                             # second platform.
./gradlew koverVerify                        # coverage gate
./gradlew migrationTest                      # fixture-vault upgrade gate
```

## 7. What only a device can show

Some guarantees are not expressible as a unit test: that a real picker returns what we think it does,
that a platform decoder produces a sensible image, that a screenshot is actually blocked, and that
plaintext is absent from a real database file on real storage. Those live as checklists in
[`../verification/`](../verification) — one per area, each stating **what must be true** rather than how
a particular run happened to check it, with the release-blocking lines marked. They are run at release
and whenever the area they cover changes.
