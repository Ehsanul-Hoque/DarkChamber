# ADR-004: v1 Ships Without the INTERNET Permission

**Status:** Accepted · **Date:** 2026-07 · **Deciders:** Project founder

## Context

DarkChamber's Phases 0–2 (vault, private camera, viewers, notes, metadata scrubber, shredder) contain no network features. A typical app template requests `android.permission.INTERNET` by default, and most dependencies assume it. Meanwhile, DarkChamber's central promise is that user files are never exfiltrated — a claim competitors make in privacy policies that users cannot verify.

## Decision

Version 1.x releases declare **no `INTERNET` permission** on Android and make the equivalent claim verifiable on iOS (no networking frameworks linked; noted in the privacy manifest / nutrition label). The release pipeline **enforces** this: the release job asserts via `apkanalyzer` that the built APK's manifest contains no `INTERNET` permission and no permissions outside a committed allowlist, and aborts the release otherwise (see `docs/engineering/RELEASE.md` §5).

## Rationale

1. **It converts a promise into a verifiable fact.** Any user or reviewer can inspect the manifest and confirm the app is *physically incapable* of sending files anywhere. No closed-source competitor can match this, and no privacy policy equals it.
2. **It's free.** Nothing in Phases 0–2 needs a network. There is no `core:network` module to accidentally depend on (see `docs/engineering/MODULES.md` §1).
3. **It hardens the supply chain.** Even a compromised third-party dependency cannot exfiltrate data from a process with no network access.
4. **It sharpens engineering discipline.** Crash reporting, analytics, and "phone home" features are structurally impossible, which keeps the no-trackers commitment from eroding one convenient SDK at a time.

## Consequences

- Crash/diagnostic triage relies on opt-in, user-readable local logs attached to GitHub issues by users themselves (`docs/engineering/PATTERNS.md` §4) and on store-console basic statistics.
- In-app update checks, remote config, and news/announcement feeds are all impossible in v1 — accepted; stores and F-Droid handle updates.
- **Phase 3 (cloud backup) necessarily adds the permission** in the builds that ship sync. This is a *documented, ADR-recorded moment*: the permission arrives together with the feature, the `core:network` module, and updated store data-safety declarations — and marketing copy must change from "no network access" to "network used only for your own cloud backup." A dedicated ADR at that time records the design (OAuth on-device, ciphertext-only egress).
- The permissions allowlist (`config/permissions-allowlist.txt`) is version-controlled; any change to it is visible in diff and requires the security-review label.
