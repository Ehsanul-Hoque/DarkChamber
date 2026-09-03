# Manual verification — SecureKeyStore (HARDWARE slot)

> Scope: the hardware-keystore stage. Verifies the two platform `SecureKeyStore` actuals
> (`AndroidSecureKeyStore`, `IosSecureKeyStore`) that CI **cannot** exercise. Run at release, and
> whenever either actual or its platform-crypto usage changes.

## Why this checklist exists

The `SecureKeyStore` **integration** logic (add / unlock / remove / coexistence with passphrase +
recovery) is fully unit-tested on the host and iOS simulator via `FakeSecureKeyStore`
(`VaultSessionTest`, `FakeSecureKeyStoreTest`). What the fake **cannot** stand in for is the real
platform secure hardware:

- Android `AndroidKeyStore` / StrongBox needs a real device (or emulator with a keystore); the key is
  non-exportable and `setUnlockedDeviceRequired` behaviour only manifests on a device.
- iOS Keychain access-control (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`) and its
  device-locked behaviour only manifest on a simulator/device, not in a common unit test.

So in CI the two actuals are **compile-gated only** (`assembleFossDebug` for Android,
`compileKotlinIosSimulatorArm64` + `iosArm64` for iOS). This document is the device verification that
covers the gap. **A release is not signed off until this checklist passes on real hardware.**

## Honesty scope (what the hardware slot is, and is not)

- **What it does:** wraps the vault's MasterKey under a platform-held, hardware-backed key so the
  vault can be unlocked on *this device* without re-entering the passphrase, gated on the device
  being unlocked.
- **What it is not:** it is **not** a per-use biometric prompt and **not** an app-lock.
  Per [ADR-002](../ADR/ADR-002-no-app-lock-feature.md) DarkChamber has no app-lock feature: the
  hardware slot protects *our own MasterKey wrap*, it does not gate the app UI. Per-use biometric
  auth (`setUserAuthenticationRequired` + `BiometricPrompt` on Android; `.biometryCurrentSet`
  `SecAccessControl` + `LAContext` on iOS) arrives with the first UI work, which has an `Activity` /
  app host to present the prompt. Do not describe the current behaviour as biometric protection.
- **Device-local:** the hardware wrap is never written to a backup manifest; it is meaningless on any
  other device. The passphrase and recovery-key slots remain the cross-device unlock paths.

## Android — `AndroidSecureKeyStore`

Run on a physical device (ideally one **with** StrongBox, e.g. a Pixel 3+, and once on a device
**without**). A build that exercises add/unlock/remove through `VaultSession` is enough; no UI is
required.

- [ ] `isAvailable()` returns `true` on the target API level (minSdk 24 … current).
- [ ] **wrap:** `addHardwareSlot` produces a `hardwareWrap`; a fresh `AndroidKeyStore` alias
      (`darkchamber.mk.hardware`) now exists (the key is created on first `wrap`).
- [ ] **unlock:** `unlockWithHardware` recovers the MasterKey — a DEK wrapped *before* the hardware
      slot was added still opens (proves it is the same MK, no data re-encryption).
- [ ] **StrongBox present:** on a StrongBox device the key is StrongBox-backed (no
      `StrongBoxUnavailableException`); wrap/unwrap round-trips.
- [ ] **StrongBox absent / fallback:** on a device without StrongBox, key generation falls back to
      the TEE-backed keystore (the `StrongBoxUnavailableException` path) and still round-trips.
- [ ] **device-unlocked gating:** with `setUnlockedDeviceRequired(true)` (API 28+), unwrap **fails**
      while the device is locked and **succeeds** once unlocked — surfaced as
      `IntegrityFailure`, never a crash.
- [ ] **key invalidation:** after a device-credential change that invalidates the key, unwrap maps
      `KeyPermanentlyInvalidatedException` to `IntegrityFailure` (not a throw); passphrase/recovery
      still unlock.
- [ ] **delete:** `removeHardwareSlot` / `deleteHardwareKey` removes the alias; a subsequent unwrap
      of a stale blob returns `IntegrityFailure`; calling delete again is a no-op (idempotent).
- [ ] **re-add:** after delete, `addHardwareSlot` regenerates a key and unlock works again.
- [ ] passphrase + recovery slots keep working through all of the above.

## iOS — `IosSecureKeyStore`

Run on the **simulator** (fast path) **and** at least once on a physical device.

- [ ] `isAvailable()` returns `true`.
- [ ] **wrap:** `addHardwareSlot` stores the MK as a Keychain generic-password item
      (service `dev.ehsanfahad.darkchamber.vault`, account `mk.hardware`) with
      `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`; `wrap` returns the 1-byte marker blob.
- [ ] **unlock:** `unlockWithHardware` recovers the MasterKey — a DEK wrapped before the slot existed
      still opens.
- [ ] **delete:** `removeHardwareSlot` / `deleteHardwareKey` calls `SecItemDelete`; a subsequent
      unwrap returns `IntegrityFailure`; a second delete is a no-op (`errSecItemNotFound` tolerated).
- [ ] **re-add:** after delete, `addHardwareSlot` re-adds the item and unlock works again.
- [ ] **overwrite:** calling `wrap` twice does not error (the delete-then-add clears any prior item —
      no `errSecDuplicateItem`).
- [ ] **device-locked behaviour (device only):** with the device locked, a Keychain read of the item
      is unavailable → unwrap returns `IntegrityFailure`; it succeeds once unlocked.
- [ ] passphrase + recovery slots keep working through all of the above.

## Confinement (re-confirm at review, not device-dependent)

- [ ] `java.security.*` / `javax.crypto.*` appear only in `SecureKeyStore.android.kt`.
- [ ] `platform.Security` appears only in `SecureKeyStore.ios.kt`.
- [ ] `CryptographyKotlinEngine` still imports only `dev.whyoleg.cryptography.*`; no new
      networking / analytics dependency; no `INTERNET` permission.
