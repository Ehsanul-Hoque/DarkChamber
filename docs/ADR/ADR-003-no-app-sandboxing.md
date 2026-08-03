# ADR-003: No Sandboxing / Isolation of Other Apps

**Status:** Accepted · **Date:** 2026-07 · **Deciders:** Project founder

## Context

Privacy-hostile apps (Facebook, Instagram, etc.) appear to "remember" users across uninstall/reinstall cycles and track activity in other apps. The idea was raised to build a sandbox system inside DarkChamber (or alongside it) that isolates such apps so they can no longer track other apps or persist data across uninstalls.

## Decision

DarkChamber will **not** include any sandboxing/isolation of other apps. A separate Shelter-style isolation app is deferred indefinitely (Phase 5 at the earliest, Android-only, and only with honest framing). The nearer-term suite candidate for this user need is a **privacy auditor/educator** app instead.

## Rationale

1. **The observed tracking is mostly not local, so a local sandbox can't stop it.** Login "memory" across reinstalls comes primarily from server-side device identification: the OS-level Advertising ID, device fingerprinting (model/screen/locale/network/DRM ID), IP address, and identity graphs — not from files hidden on the device. "Off-Facebook Activity" reflects **other** apps and websites embedding Meta's SDK/Pixel and reporting from their side; the Facebook app is not reading other apps' data locally (the OS sandbox already prevents that).
2. **What a sandbox *can* do, the OS increasingly does.** Android work profiles (the mechanism behind Shelter/Island) provide OS-enforced storage/contacts/account separation and app freezing. Android 15's **Private Space** ships much of this natively; on iOS, third-party isolation is not buildable at all.
3. **Honesty constraint.** Marketing an isolation feature as "stops Facebook tracking" would be false — the dominant channels (Advertising ID, SDK-side reporting, fingerprinting, IP) survive isolation. Our brand principle is honesty over marketing; we do not ship features whose implied promise exceeds their real effect.
4. **Cost/scope.** A device-policy-controller app is deep Android-internals work with heavy support burden, shrinking value post-Private-Space, and zero iOS story. It does not share meaningful code with DarkChamber's crypto core.

## Consequences

- DarkChamber's permission footprint and scope remain minimal; the vault does not become a "security suite."
- User need is addressed honestly via education and a possible future **privacy auditor** companion app: surface trackers embedded in installed apps (e.g., Exodus Privacy data), guide users to reset/delete the Advertising ID, disable Off-Facebook Activity, enable App Tracking Transparency (iOS), and set up Private Space/Shelter (Android).
- If a standalone isolation app is ever built (Phase 5+), it must be positioned as "freeze and separate" — never as "stop tracking."
