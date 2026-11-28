# Testing & Code Quality

> Part of the [Engineering Guide](./README.md). Tests are written before implementation; coverage and architecture rules gate CI.

## 1. Tooling

| Tool                        | Used for                                                                    |
|-----------------------------|-----------------------------------------------------------------------------|
| `kotlin-test`               | assertions, common test entry points                                        |
| `kotlinx-coroutines-test`   | `runTest`, virtual time, test dispatchers                                   |
| **Turbine**                 | asserting `StateFlow`/`Flow` emission sequences (MVI)                       |
| in-memory SQLDelight driver | repository/local tests without a device                                     |
| Compose `ui-test`           | Compose UI tests, written once in `commonTest` (§8)                         |
| **Robolectric**             | the Android framework on the JVM, so those UI tests run on the host (§8)    |
| `koin-test`                 | verifying module graphs resolve                                             |
| **Konsist**                 | architecture + security-invariant rules ([`MODULES.md`](./MODULES.md) §4.2) |
| **Roborazzi** (optional)    | screenshot tests for secure-screen states                                   |

## 2. What to test at each layer

- **`core:crypto`** — the crown jewels; see §3. Pure JVM tests, no mocks of the crypto itself.
- **domain** — use cases in pure JVM tests with fake repositories.
- **data** — repositories against in-memory SQLDelight + a temp-dir `core:storage`; assert mapping and that plaintext never lands outside the sandbox (the fake storage asserts path prefixes).
- **presentation** — ViewModels with Turbine: feed intents, assert exact `State`/`Effect` sequences, including the lock-mid-operation path (session death must surface `AuthRequired`, never a crash or a stale decrypted state).
- **screens** — a decision a screen makes belongs in a tested function; what only the drawn screen can show (which message, which button is enabled, which callback a tap reaches) is a Compose UI test (§8).

## 3. Security-critical gates (the RLS-equivalent)

The highest-value tests in DarkChamber guard the two things that can silently destroy user trust: **the format** and **migrations**. Both are mandatory CI gates — a release cannot be cut if either fails.

### 3.1 Format test vectors ([`FORMAT.md`](../FORMAT.md) §7)

`docs/format-test-vectors/` holds, per cipher_id: fixed keys/nonces/plaintexts with expected ciphertext for single-chunk, multi-chunk, and empty files, **plus negative vectors that MUST fail** — bad tag, reordered chunks, truncated stream, wrong `is_last_flag`, altered plaintext prelude. `core:testing` ships the loader; the suite runs on every commit.

**Enforce completeness, don't rely on memory:** a meta-test enumerates all shipped format versions and cipher_ids (read from one constant in `core:crypto`) and asserts each has a vector set — adding a cipher without vectors fails the suite, exactly the pattern that kept RLS policies honest in the sibling project.

### 3.2 Migration gate

CI runs an upgrade test **from every previously released schema and format version**: fixture vaults (tiny, committed per release) are opened by the current build; every item must decrypt and every catalog row survive. A failed migration must fail *closed* in production code (refuse to run, vault untouched). "Old vaults always open" is a release-blocking invariant, per ARCHITECTURE.md §14.

### 3.3 Property/round-trip fuzzing (planned — not in the tree yet)

Planned: randomized round-trips (encrypt → decrypt == identity across sizes 0..multi-chunk; random truncations/bit-flips must fail authentication), run in the PR suite with a bounded iteration count and nightly with a larger one. Until it exists, the codec's own tests cover these cases with fixed inputs, and the format vectors (§3.1) with committed ones.

## 4. Coverage policy — Kover

**What is enforced: floors, measured.** Kover runs per module (never merged) over what the JVM host tests
execute, and each module is gated at the **line coverage measured for it**, rounded down to the whole percent.
A floor only ever rises: raise a module's number in the `darkchamber.quality` plugin, and in the table below,
when its coverage does. A
module Kover measures but the plugin gives no floor fails the build.

| Module               | Floor (line %) |
|----------------------|----------------|
| `:core:vault`        | 96             |
| `:core:storage`      | 91             |
| `:core:crypto`       | 88             |
| `:core:db`           | 88             |
| `:core:designsystem` | 81             |
| `:core:model`        | 65             |
| `:feature:vault`     | 62             |
| `:feature:camera`    | 61             |
| `:core:logging`      | 56             |
| `:app`               | 42             |

Not measured: `:core:testing` (test support), `:konsist` (a test-only JVM module), `:androidApp` (a thin
host). Code in `iosMain` is never compiled for the JVM, so it is absent from the numbers — the iOS simulator
suite covers it, unmeasured. Excluded: generated code (SQLDelight's classes, named in `:core:db`'s build file).

**Where the floors are headed: the targets.** Not gates yet — a floor set to a target instead of a measurement
would be a gate that lies.

| Scope                 | Target                                                        |
|-----------------------|---------------------------------------------------------------|
| `core:crypto`         | **95** (then 100; every uncovered line justified in the PR)   |
| domain (all features) | 90                                                            |
| data / presentation   | 80                                                            |
| overall               | 80                                                            |

CI uploads each module's Kover HTML and XML report as the `kover-reports` artifact of the `quality` job, kept
when a gate fails. There is no coverage comment on the PR: it would need write permission and another
third-party action.

## 5. Quality tools

Applied uniformly via the `darkchamber.quality` convention plugin (every KMP module, `:androidApp`,
`:konsist`, and the root for the build scripts); configs live at repo root.

| Tool                       | Config                     | Enforces                                         | Task                              |
|----------------------------|----------------------------|--------------------------------------------------|-----------------------------------|
| **Spotless** (ktlint 1.8)  | `.editorconfig`            | formatting + ktlint and Compose rules            | `spotlessCheck` / `spotlessApply` |
| **detekt** (2.0 alpha)     | `config/detekt/detekt.yml` | static analysis, complexity                      | `detekt`                          |
| **Konsist**                | `:konsist` module          | architecture + security invariants               | `:konsist:test`                   |
| **Kover**                  | quality plugin             | per-module coverage floors (§4)                  | `koverVerify`                     |

**ktlint runs through Spotless**, not the ktlint Gradle plugin, which has an open configuration-cache bug in
a build that keeps the cache on. Spotless hands ktlint the standard settings from `.editorconfig` but not the
Compose rule set's own (`compose_*`), so the plugin reads those lines and passes them on — `.editorconfig`
stays their one home. A rule a file cannot follow is suppressed at the site, with the reason beside it; the
whole-repo reformat commit is listed in `.git-blame-ignore-revs` ([`WORKFLOW.md`](./WORKFLOW.md) §1).

**detekt is a 2.0 pre-release, pinned exactly**, because the 1.23 line cannot read the metadata of the Kotlin
this project builds with and gets no more releases. Only the plain `detekt` task runs: the type-resolution
tasks carry the alpha's open false positives on `expect`/`actual` pairs. When 2.0 is stable, bump it and turn
type resolution on. `config/detekt/detekt.yml` holds only departures from the defaults, each with its reason
— among them, detekt's test-path leniency re-declared to include `androidHostTest`, which its defaults miss.
Each module keeps a baseline of the findings it had when detekt arrived; **never in `core:crypto`**, whose
findings were fixed (named constants for the format's numbers, a scoped `@Suppress` with a reason where the
structure follows the format). A baseline entry is a rule and the declaration it fired in, not a line, so it
lets through more than the findings it lists: a new finding of a rule already recorded for the same
declaration passes too — a second `catch (e: Exception)` in a class whose baseline already holds one. Review
covers those, and burning a baseline down removes the gap. The Compose rules run once, in ktlint, not
again in detekt.

**Konsist is the exception.** It is not run by the quality plugin: its rules are ordinary tests in their own
module, so they run as that module's test task and there is no aggregate `konsistTest`.

**The workflow files are checked too**, by the `quality` job before anything else: `check-jsonschema` against
the GitHub Actions schema and `yamllint --strict` with `config/yamllint.yml`, over `pr.yml` and every
`*.yml.disabled` template.

## 6. Running

```bash
./gradlew :konsist:test                      # architecture + security invariants
./gradlew spotlessApply                      # fix formatting — before committing
./gradlew spotlessCheck detekt               # formatting + static analysis, as CI runs them
./gradlew testAndroidHostTest                # fast JVM suite (incl. format vectors and the UI tests)
./gradlew iosSimulatorArm64Test              # iOS, on a simulator; runs on pull requests that touch
                                             # shared code (gated to non-draft, see RELEASE.md). Worth
                                             # running locally before any iOS-visible change: it is the
                                             # only suite that exercises the native SQLite driver, the
                                             # iOS platform seams, and the codec, header and key-wrap
                                             # suites on a second platform (the committed vectors run
                                             # on the host only).
./gradlew koverVerify                        # per-module coverage floors
./gradlew koverHtmlReport                    # per module: <module>/build/reports/kover/html
./gradlew migrationTest                      # fixture-vault upgrade gate (not yet: after v1.0)
```

## 7. What only a device can show

Some guarantees are not expressible as a unit test: that a real picker returns what we think it does,
that a platform decoder produces a sensible image, that a screenshot is actually blocked, that
**nothing in a window reaches the platform's autofill and content-capture structures** — a channel the
screenshot flag does not cover, and one only the platform can be asked about — and that plaintext is
absent from a real database file on real storage. Those live as checklists in
[`../verification/`](../verification) — one per area, each stating **what must be true** rather than how
a particular run happened to check it, with the release-blocking lines marked. They are run at release
and whenever the area they cover changes.

## 8. Compose UI tests

**Written once, run on both platforms.** A UI test is a class in a module's `commonTest` that extends
`UiTest` from `:core:testing` and uses Compose's `runComposeUiTest` (the `v2` one). On the Android host it runs
under Robolectric — `UiTest`'s Android side carries `RobolectricTestRunner`, which gives the JVM a real Android
framework for Compose to compose, measure and draw on — and on the iOS simulator it runs natively. Only `UiTest`'s
subclasses pay Robolectric's start-up; a module's other host tests stay plain JUnit.

**Opting a module in** is one line beside its `darkchamber.kmp.*` plugin: `id("darkchamber.compose.uitest")`
([`MODULES.md`](./MODULES.md) §5). It brings `:core:testing` and Compose's test API, the merged manifest and
resources Robolectric reads, and the opt-in to the experimental test API.

**The helpers** (`:core:testing`): `setThemedContent { }` draws inside `DarkChamberTheme`, as the app does;
`onButton(label)` finds the button with exactly that label, enabled or not — never a title that shares its
words; `hasRole(role)`; `assertNoText(text)` fails if any node shows the text, alone or inside a sentence — for
the lines a screen must **not** say in a state (hard rule 6).

**Finding things:**

- **By what the user sees or hears, never by a retyped word.** Expected text comes from the same format
  function the screen calls (`GalleryFormat.emptyListing(…)`, `ViewerFormat.cancel()`), so rewording or
  translating changes both sides together. A label still written as a literal in a layout moves into its format
  object first, in a commit of its own.
- **Labels, not test tags.** The label is often the claim under test ("Deleting…" in place of "Delete 3"), and
  `onButton` checks what a screen reader announces — the text and the button role. A test tag passes whatever
  the button says. Tags only where nothing perceivable identifies a node (an unlabelled thumb, one tile among
  same-named ones).
- An icon button is found by its content description; a text field by its label — a **disabled** field drops
  its set-text action, so `hasSetTextAction()` cannot find it.

**Through the parent screen.** Drive the screen with a state and no-op callbacks except the ones the test
asserts (`TestGalleryScreen`, `TestViewerScreen` in `:feature:vault`'s tests), rather than widening a private
composable's visibility to test it. To walk a dialog through states in one test, hold its input in a
`mutableStateOf` the test reassigns.

**Test the screen's decision, and prove the test can fail.** The decision functions under a screen have their
own unit tests; a UI test asserts the screen *uses* them — which message it draws, which button is spent, which
callback a tap reaches. Each UI test is mutation-checked against the screen's own wiring on both platforms (a
swapped callback, a guard forced true, the decision function bypassed), with a control mutation that must
survive. What a test cannot show — pixels, fonts, real touch, a vendor's quirks — stays with the device checks
of §7.
