# Manual verification — app cold-open bootstrap

> Scope: cycle 5a. Verifies the app host + composition root — `VaultBootstrap` cold-open, platform
> vault-root resolution, catalog + storage wiring, and the iOS `Shared` framework — on real
> hardware and in Xcode, covering what the automated smoke tests cannot. Run at release, and
> whenever the bootstrap, platform resolution, DI graph, or the iOS framework export changes.

## What is already automated (run these first)

The cold-open + encrypted round-trip is proven by fast tests before you touch a device:

- **Common (host + iOS sim):** `./gradlew :app:testAndroidHostTest :app:iosSimulatorArm64Test`
  — the Koin graph resolves; `VaultBootstrap.selfCheck()` round-trips against a temp root +
  in-memory driver + `FakeSecureKeyStore`; create → reopen (unlock) + wrong-passphrase paths.
- **iOS-runtime specifics (iOS sim):** `IosVaultRootResolverTest` (in `:app:iosSimulatorArm64Test`)
  resolves the real `<Documents>/vault`, asserts the deferred cycle-3 `isExcludedFromBackup == true`
  runtime call took, and cold-opens + round-trips through `runVaultSelfCheck()` on the simulator.
- **Android instrumented (device/emulator):**
  `./gradlew :androidApp:connectedFossDebugAndroidTest` — boots the real Koin graph with a real
  `Context`; asserts `<filesDir>/vault/db/catalog.db` + `keys/mk.wrapped` exist, an encrypted
  `.dchm` object is written, and **nothing lands in external/shared storage** (hard rule #1).
  Not wired into CI this cycle (the instrumented/quality job is still deferred).

## iOS — the `Shared` framework in Xcode

The Kotlin `iosSimulatorArm64Test` above already exercises the real iOS bootstrap code. This section
covers the **Swift ↔ framework boundary** the app itself uses, which the Gradle tests do not.

### One-time: build the app against the framework

1. Open `iosApp/iosApp.xcodeproj` in Xcode (26.x).
2. Select the `iosApp` scheme + an iOS Simulator (e.g. iPhone 17) and build (⌘B).
   - The **Compile Kotlin Framework** run-script phase invokes
     `./gradlew :app:embedAndSignAppleFrameworkForXcode`, which builds + embeds `Shared.framework`.
   - `Config.xcconfig` points `FRAMEWORK_SEARCH_PATHS` + `-framework Shared` at it; `import Shared`
     should resolve with no further wiring.
3. Run the app (⌘R). On launch `iOSApp.init()` calls `IosBootstrapKt.runVaultSelfCheck()` and logs
   `DarkChamber vault self-check: OK` to the console. Confirm it prints **OK**.

### The XCTest target (already wired)

The `iosAppTests` unit-test bundle is committed in `iosApp.xcodeproj` (added via the Xcode UI —
Unit Testing Bundle, **Target to be Tested = iosApp**, so `TEST_HOST` / `BUNDLE_LOADER` load the
app's linked `Shared.framework`). Its case source is
`iosApp/iosAppTests/DarkChamberVaultTests.swift`. Two build settings the static framework requires,
already applied:

- **App (`Config.xcconfig`):** `OTHER_LDFLAGS = … -framework Shared -lsqlite3`. `Shared` is a
  **static** framework, so SQLDelight's sqliter cinterop is linked into the app at Xcode-link time
  and needs system `sqlite3` — without `-lsqlite3` the app fails with ~31 undefined `_sqlite3_*`
  symbols. (Gradle's `linkerOpts("-lsqlite3")` covers only Gradle-produced binaries, not the Xcode
  app link.)
- **`iosAppTests` target:** **Framework Search Paths** =
  `$(SRCROOT)/../app/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` so `import Shared` resolves
  in the test (the test target does not inherit the app's `Config.xcconfig`).

### Run the XCTest

- In Xcode: ⌘U, or
- CLI: `xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
    -destination 'platform=iOS Simulator,name=iPhone 17'`

Both cases must pass (verified green on the simulator, cycle 5a):
- `testColdOpenSelfCheckSucceeds` — cold-open + encrypted round-trip via the framework.
- `testVaultRootIsExcludedFromBackup` — `<Documents>/vault` has `isExcludedFromBackup == true`.

<!-- TASK-010 appends the on-device physical-device manual checklist (first-run create, kill+relaunch
reopen, app-private vault dir, iOS backup-exclusion inspection, no plaintext in shared storage). -->
