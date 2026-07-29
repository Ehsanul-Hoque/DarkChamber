# ADR-002: No App-Lock Feature

**Status:** Accepted · **Date:** 2026-07 · **Deciders:** Project founder

## Context

Competing "security suite" apps (e.g., Sanctum: Security & App Lock) offer app locking — requiring a PIN/biometric to open other apps on the device. Users may expect a security-branded app to include this, and feature requests are anticipated.

## Decision

DarkChamber will **not** implement app locking, neither in this app nor as a separate app in the suite.

## Rationale

1. **The mechanism is inherently invasive.** Android has no API for locking other apps. App lockers detect foreground-app changes via **Usage Access** or an **Accessibility Service** and race to draw an overlay lock screen via **Display Over Other Apps**. This is the same permission trio used by spyware and stalkerware.
2. **It contradicts our security posture.** Our threat model and Play data-safety story rest on a minimal permission footprint (explicitly: no accessibility service, no overlay, no usage access). One feature would undo the brand promise "verify us — we can't see anything we shouldn't."
3. **It is unreliable in practice.** OEM battery managers kill the watcher service, so the lock screen intermittently fails to appear — a security feature that silently fails is worse than none. It is also trivially bypassed (safe mode, uninstalling from Settings).
4. **Policy risk.** Google Play's Accessibility-API declaration process regularly rejects non-accessibility uses; overlay permissions face increasing restrictions. Apple does not permit third-party app locking on iOS at all (reserved for Screen Time), so the feature could never be cross-platform.
5. **The OS has absorbed the need.** Native biometric app locking now ships in Android 15+, iOS 18+, and every major OEM skin. Building a worse, permission-hungry version of a built-in feature has negative value.

## Consequences

- Feature requests for app lock are answered by linking this ADR and pointing users to the OS-native equivalents (Android app pinning/Private Space, iOS Screen Time / lock-an-app, OEM secure folders).
- DarkChamber protects **its own** contents with biometric/PIN unlock — that is in scope and unaffected.
- Revisit only if both platforms ship a sanctioned, non-accessibility API for third-party app locking (not currently on any roadmap).
