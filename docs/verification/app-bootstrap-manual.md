# Manual verification — app cold-open bootstrap

> Scope: the app-host stage. Verifies the app host + composition root — `VaultBootstrap` cold-open, platform
> vault-root resolution, catalog + storage wiring, and the iOS `Shared` framework — on real
> hardware and in Xcode, covering what the automated smoke tests cannot. Run at release, and
> whenever the bootstrap, platform resolution, DI graph, or the iOS framework export changes.
>
> **Historical note.** An earlier diagnostic launch probe (`runVaultSelfCheck` / `iOSApp.init` /
> `VaultBootstrap.selfCheck()`) is **removed** — the app now boots to a real create/unlock UI. The
> **on-device flow** below is superseded by
> [`session-gate-manual.md`](./session-gate-manual.md); this doc's still-current value is the iOS
> `Shared`-framework wiring (build settings, the XCTest target) and the app-private/backup spot-checks.

## What is already automated (run these first)

The cold-open + encrypted round-trip is proven by fast tests before you touch a device:

- **Common (host + iOS sim):** `./gradlew :app:testAndroidHostTest :app:iosSimulatorArm64Test`
  — the Koin graph resolves; `VaultBootstrapTest` drives `createVault` → reopen (`unlock`) against a
  temp root + in-memory driver + `FakeSecureKeyStore`, plus the wrong-passphrase + biometric paths.
- **iOS-runtime specifics (iOS sim):** `IosVaultRootResolverTest` (in `:app:iosSimulatorArm64Test`)
  resolves the real `<Documents>/vault`, asserts the `isExcludedFromBackup == true`
  runtime call took, and `createVault` → `unlock`s through the real iOS graph on the simulator.
- **Android instrumented (device/emulator):**
  `./gradlew :androidApp:connectedFossDebugAndroidTest` — boots the real Koin graph with a real
  `Context`; asserts `<filesDir>/vault/db/catalog.db` + `keys/mk.wrapped` exist, an encrypted
  `.dchm` object is written, and **nothing lands in external/shared storage** (hard rule #1).
  Not wired into CI yet (the instrumented/quality job is still deferred).

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
3. Run the app (⌘R). `ContentView` hosts `MainViewControllerKt.MainViewController()` (the shared
   Compose gate); the app should launch to the **create/unlock** screen. (First run shows Create only
   on a clean container — see the dev-reset note in `session-gate-manual.md`.)

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

Both cases must pass (verified green on the simulator):
- `testMainViewControllerBuildsThroughTheFramework` — the shared Compose gate builds through the
  framework boundary (Koin graph + Decompose root construct).
- `testVaultRootIsExcludedFromBackup` — `<Documents>/vault` has `isExcludedFromBackup == true`.

## On-device manual checklist (physical hardware)

The automated tests above run on a host, an emulator, and a simulator. This section is the
**physical-device backstop** (mirrors the SecureKeyStore checklist): the real filesystem
sandbox, real Keystore/Keychain, and real backup behaviour only fully manifest on a shipped device.
Run it at release and whenever the bootstrap, platform resolution, or backup posture changes.

> The **create/unlock/biometric flow** is now driven through the real UI — follow
> [`session-gate-manual.md`](./session-gate-manual.md) for that. The steps below are the
> storage/backup spot-checks: create a vault via the UI first, then verify the filesystem posture.

### Android — physical device

1. **First run (create).** Fresh install `:androidApp` (`assembleFossDebug`) on a device; create a
   vault through the UI. Any logs stay content-free (Logcat, tag `DarkChamber` — identifiers/codes
   only, never content: hard rule #4).
2. **Kill + relaunch (reopen/unlock).** Force-stop the app, relaunch → it opens on the **Unlock**
   screen and unlocks the existing vault via the persisted keystore (not a recreate).
3. **Vault is app-private.** With the app **debuggable**, confirm the vault lives under app-private
   internal storage and nothing under a world-readable path:
   ```
   adb shell run-as dev.ehsanfahad.darkchamber ls -R files/vault
   ```
   Expect `db/catalog.db`, `keys/mk.wrapped`, and `objects/…/<id>.dchm`. There is no vault data under
   `/sdcard`, external files, or MediaStore.
4. **No plaintext in shared storage.** Confirm external dirs hold no vault artifacts:
   ```
   adb shell ls -R /sdcard/Android/data/dev.ehsanfahad.darkchamber 2>/dev/null
   ```
   Nothing vault-related (no `.dchm`, no plaintext). (The instrumented test already asserts this; the
   manual pass is the belt-and-suspenders on a real device.)
5. **Backup denied.** The manifest sets `allowBackup="false"` + deny-all extraction/backup rules.
   Optional spot-check: `adb backup` produces no app data.

### iOS — physical iPhone

1. **First run (create).** Build + run `iosApp` on a device; create a vault through the UI.
2. **Kill + relaunch (reopen/unlock).** Swipe-kill the app, relaunch → it opens on the **Unlock**
   screen and reopens the existing vault via the persisted Keychain/keystore path (not a recreate).
3. **Vault is app-private + backup-excluded.** The vault is under the app container's `Documents`,
   which is app-private by sandbox. Confirm the `isExcludedFromBackup == true` on the vault
   root holds on-device (the XCTest asserts it on the simulator; on a device it is what keeps the
   encrypted vault out of iCloud/iTunes backups). If inspecting a device backup, confirm no
   `Documents/vault` payload is present.
4. **No plaintext leaves the vault.** No vault content is written to a shared container, Files-app
   location, or Photos — decrypted bytes live only in memory (hard rule #1).

### Sign-off

A release is not signed off until this checklist passes on **both** a physical Android device and a
physical iPhone. Record the device models + OS versions in the release notes (as the SecureKeyStore
checklist requires). This checklist proves cold-open + storage/backup posture only; the per-use biometric
prompt and session/lock gate have their own checklist (session-gate-manual.md).
