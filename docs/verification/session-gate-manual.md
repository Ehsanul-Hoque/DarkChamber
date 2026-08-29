# Manual verification — session gate + biometric unlock + lock lifecycle

> Scope: cycle 5b. Verifies the structural session gate, the **per-use biometric binding**, the
> passphrase fallback, biometric invalidation, and the lock lifecycle (manual + on-background) on
> **real hardware** — the parts the automated tests deliberately cannot reach (a real Keystore /
> Keychain and a real Face/Touch ID prompt). Run at release, and whenever `core:crypto`'s
> `SecureKeyStore`/`BiometricAuthenticator` actuals, the session gate, or the lock lifecycle change.

## What is already automated (run these first)

Everything except real hardware auth is proven by fast tests before you touch a device:

- **Common (host + iOS sim):** `./gradlew :app:testAndroidHostTest :app:iosSimulatorArm64Test`
  — the session-gate transitions (`RootComponentTest`), the create → recovery → enable → unlock
  state machine and the biometric approve/deny/**invalidated → forced-passphrase** paths
  (`LockedComponentTest`), and the lock teardown — zeroize MK, purge `tmp/`, close the catalog
  driver, dispose the session scope, on manual lock **and** `onStop` (`UnlockedComponentTest`).
- **core:crypto (host + iOS sim):** `VaultSessionTest` drives the now-`suspend`
  `unlockWithHardware`/`addHardwareSlot` through `FakeBiometricAuthenticator` — enroll → unlock,
  deny → `Err`, invalidated → `IntegrityFailure`, and the passphrase still unlocks after the
  hardware slot dies (§3.3).
- **iOS runtime (iOS sim):** `IosVaultRootResolverTest` builds the real graph and create → unlocks
  by passphrase on the simulator, and asserts `<Documents>/vault` is `isExcludedFromBackup`.

The two platform `SecureKeyStore`/`BiometricAuthenticator` actuals are **compile-gated only** — the
real Keystore/Keychain + biometric prompt need a device, which is what this checklist covers.

## Build + install

- **Android (physical Pixel):** `./gradlew :androidApp:installFossDebug` (device connected, USB
  debugging on), or Run from Android Studio. Requires an enrolled fingerprint/face on the device.
- **iOS (physical iPhone):** open `iosApp/iosApp.xcodeproj`, pick your device + team, Run (⌘R).
  Requires Face/Touch ID enrolled.

> **Dev reset.** The gate shows **Unlock** whenever a keystore already exists. To force the
> first-run **Create** path, wipe the app's data first: Android `adb uninstall
> dev.ehsanfahad.darkchamber.DarkChamber…` (or delete the app); iOS delete the app from the device /
> `xcrun simctl uninstall`. There is no in-app reset yet (panic-wipe is Phase 2).

## Checklist (run on BOTH a physical Pixel and a physical iPhone)

### A. First-run create + recovery key
- [ ] Fresh install opens on **Create your vault** (not Unlock).
- [ ] The create copy is honest — it says the passphrase can't be reset, no false promises.
- [ ] Set a passphrase → the **recovery key** is shown, grouped, in mono.
- [ ] The recovery-key copy states plainly that **loss is unrecoverable** and that we never see it
      (no promise beyond `docs/THREAT_MODEL.md`).
- [ ] "I've saved it" advances; you cannot skip past the recovery key without confirming.

### B. Enable biometric (the per-use binding)
- [ ] The **Enable biometrics?** step appears; the copy says the passphrase still works.
- [ ] Tap **Enable biometrics** → a **real Face/Touch ID prompt appears** (this is the enrollment
      wrap; on Android a symmetric auth-required key prompts once here).
- [ ] Approving lands on the unlocked placeholder ("Vault unlocked").
- [ ] "Not now" also reaches the unlocked placeholder (biometric stays optional).

### C. Returning-user biometric unlock
- [ ] Fully close the app (swipe it away / kill it), reopen → opens on **Unlock** with a
      **Unlock with biometrics** primary action.
- [ ] Tap it → a real biometric prompt; approving opens the vault.
- [ ] **Cancel** the biometric prompt → you can still unlock with the **passphrase** (fallback),
      and a wrong passphrase shows the honest "didn't work" error, not a crash.

### D. Biometric invalidation → forced passphrase (§3.3)
- [ ] With the app closed, **enroll a new fingerprint / re-enroll Face ID** in device Settings.
- [ ] Reopen the app, attempt biometric unlock → it fails and the screen **forces the passphrase**
      (the biometric CTA is gone), with copy explaining biometrics changed.
- [ ] Unlock with the passphrase succeeds (the passphrase + recovery slots are untouched).
- [ ] Re-enable biometrics from the unlocked state → a fresh prompt; subsequent relaunch unlocks by
      biometric again.

### E. Lock lifecycle (hard rules #1 / #5)
- [ ] From the unlocked placeholder, tap **Lock** → returns to the Unlock screen.
- [ ] Unlock again, then send the app to the **background** (Home) and reopen → it is **Locked**
      (asks to unlock), i.e. the session did not survive backgrounding.
- [ ] Android only: **rotate** the device while unlocked → it stays **unlocked** (a configuration
      change keeps the retained session; only a real background locks).
- [ ] No decrypted scratch survives a lock: after locking, the app-private `tmp/` holds nothing
      (there is no viewer yet this cycle, so this is mostly a forward guarantee — confirm `tmp/` is
      empty via `adb shell run-as … ls files/vault/tmp` on Android).

### F. Honesty + confinement (spot check)
- [ ] No screen promises more than `docs/THREAT_MODEL.md` allows (no "military-grade", no
      "permanently erased", no "we can recover it").
- [ ] Android: the app requests **no INTERNET** and only the allowlisted biometric permissions
      (`aapt dump permissions` / the release permission audit) — `USE_BIOMETRIC` (+ legacy
      `USE_FINGERPRINT`), `CAMERA`, `RECORD_AUDIO`; nothing else user-facing.

## Sign-off

Record device models + OS versions, date, and the app version (`:androidApp:printVersion`). Any
failure in D or E is release-blocking (a security invariant); A–C/F failures are UX/correctness bugs
to fix before release.
