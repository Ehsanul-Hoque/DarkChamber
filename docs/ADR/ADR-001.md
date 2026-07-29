# ADR-001: No Auto-Encryption of Photos Taken by Other Apps

**Status:** Accepted · **Date:** 2026-07 · **Deciders:** Project founder

## Context

The original product vision included a background service that would detect photos/videos created by *other* apps (e.g., the stock camera writing to `DCIM/Camera`) and encrypt them immediately — before cloud services like Google Photos could back them up unencrypted.

## Decision

We will **not** build this feature. The private in-app camera writing directly to encrypted, app-private storage is our answer to the same user need.

## Rationale

1. **Android permissions make it non-viable.** Under scoped storage (Android 11+), modifying or deleting another app's media files requires a per-operation system confirmation dialog (`MediaStore.createWriteRequest`/`createDeleteRequest`). The only bypass is `MANAGE_EXTERNAL_STORAGE` (All Files Access), which Google Play restricts to file managers, backup, and anti-virus apps; a photo-encryption utility requesting it risks rejection or removal.
2. **Timely detection is unreliable.** WorkManager's minimum periodic interval is 15 minutes and is deferred by Doze. Near-instant reaction requires a persistent foreground service with a `FileObserver`, which OEM battery managers (Samsung, Xiaomi, etc.) routinely kill — so captures get silently missed.
3. **We'd be racing a system uploader.** Google Photos backup runs via Play Services and can upload new photos within seconds on Wi-Fi. Even a perfectly-alive service cannot guarantee it wins the race, and a feature that "usually" protects photos is worse than one that honestly doesn't exist.
4. **Impossible on iOS.** Third-party apps cannot silently modify or delete Photos-library assets; `PHPhotoLibrary` deletion requires user confirmation and there is no background hook. Any Android implementation would be a platform-exclusive fragile feature.
5. **The reliable design achieves the actual goal.** Photos captured with DarkChamber's own camera go straight into encrypted app-private storage, which shared-storage scanners (including Google Photos backup) never see. Plaintext never exists in shared storage at all — a stronger guarantee than post-hoc encryption could ever provide.

## Consequences

- The in-app camera is a core, non-negotiable feature rather than a convenience.
- For stock-camera photos, our guidance to users is honest: exclude folders from Google Photos backup, or use OS containers (Android 15 Private Space, Samsung Secure Folder, Google Photos Locked Folder), then import into DarkChamber.
- Revisit only if Google grants a written `MANAGE_EXTERNAL_STORAGE` exception — and even then, Android-only, opt-in, and explicitly labeled best-effort.
