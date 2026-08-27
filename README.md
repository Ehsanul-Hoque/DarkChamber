# DarkChamber 📷🔒

> Long before photography, a *camera* was just a chamber: a dark, sealed room where images formed, seen by no one outside. DarkChamber brings that idea back — **a private camera and an encrypted vault in one. What enters the chamber never leaves it.**

A Kotlin Multiplatform app for **Android** and **iOS** that encrypts, stores, and views your private files — photos, videos, audio, PDFs, notes, and documents — with modern, verifiable cryptography. Photos taken with the built-in camera never touch shared storage, so cloud services (Google Photos included) never see them.

**Free and open source (GPLv3). Local-first. No ads, no trackers, no accounts — and v1 ships with no `INTERNET` permission, so you can verify in the manifest that your files cannot leave your device.**

> ⚠️ **Status: pre-release.** The multi-module KMP skeleton is in place — `:build-logic` convention plugins and both the Android and iOS hosts build. The encrypted **`.dchm` container format** is implemented in `:core:crypto` (chunked AES-256-GCM behind a swappable `CryptoEngine`), and so is the portable **key hierarchy** — MasterKey/per-object DEK wrapping, a CBOR `KeyStore` (**Argon2id** passphrase slot with PBKDF2 as a supported fallback + HKDF recovery slot), a Crockford-base32 recovery key, and an in-memory `VaultSession` — both checked against committed [format & KeyStore test vectors](./docs/format-test-vectors); the **hardware-backed** key slot is built too (Android Keystore/StrongBox, iOS Keychain behind a `SecureKeyStore` seam, gated on the device being unlocked). The **storage layer** now exists as well: `:core:storage` ships a `VaultStorage` facade over an injected vault root — atomic object/thumbnail/key-blob stores and purge-on-lock `tmp/` scratch — and the Android manifest denies OS/cloud backup. The **catalog** is built too: `:core:db` ships schema v1 (`items`/`albums`/`audit_log`) and a fail-closed, `Outcome`-returning `CatalogStore` over an injected SQLDelight driver (encrypted-BLOB columns + plaintext index columns only, a reactive item-list `Flow`, no `core:crypto` dependency; platform driver creation deferred to the app cycle). The **app host** now exists too: a shared `:app` composition root wires the Koin graph and the platform vault-root resolution (Android `filesDir` / iOS `Documents` + `isExcludedFromBackup`), and its `VaultBootstrap` cold-opens the vault (passphrase) and round-trips one encrypted object end-to-end — proven on a physical Android device and the iOS simulator; `:core:logging` graduated to a Kermit logger with a `VaultError` redaction boundary, and `:app`'s `iosMain` exports the `Shared` umbrella framework that the SwiftUI host links. The remaining `:core:*` modules (`designsystem`/`testing`) are still stubs. But there is no **per-use biometric unlock** or vault UI yet — that (with the session/lock gate) is the next cycle; the documents under [`docs/`](./docs) describe the target design, and **nothing here is ready to protect real files.**

---

## ✨ What it does (v1)

- **Private camera** — photos & video captured straight into encrypted, app-private storage. Plaintext never touches disk outside the sandbox; Google Photos has nothing to back up.
- **Encrypted vault** — import any file (images, videos, audio, PDFs, documents); everything is encrypted with AES-256-GCM under hardware-backed keys (Android Keystore/StrongBox, iOS Keychain).
- **View without exposing** — in-app viewers decrypt to memory: images, streaming video/audio with seeking, PDFs. Anything else opens via an explicit, warned "decrypt & open in…" flow.
- **Encrypted notes** — Markdown notes stored as first-class vault items.
- **Metadata scrubber** — inspect and strip EXIF/GPS/XMP from files before sharing, with a before/after diff. Works standalone, without importing.
- **Honest shredder** — vault items are *crypto-shredded* (key destroyed — genuinely irreversible). For external files we do a best-effort overwrite and tell you plainly what flash storage can and can't guarantee. No "military-grade wipe" theater.
- **Biometric/PIN unlock, recovery key, screenshot protection, decoy vault** *(phased — see the roadmap)*.
- **Later (paid, one-time):** zero-knowledge encrypted backup to *your own* Google Drive/iCloud. We run no servers and can never see your files. The F-Droid build ships it free.

What we deliberately **don't** do — and why — is documented too: [no auto-encryption of other apps' photos](./docs/ADR/ADR-001-no-auto-encryption-of-other-apps-photos.md), [no app lock](./docs/ADR/ADR-002-no-app-lock-feature.md), [no sandboxing of other apps](./docs/ADR/ADR-003-no-app-sandboxing.md), [no network access in v1](./docs/ADR/ADR-004-no-internet-permission-v1.md).

---

## 🔍 Verify, don't trust

DarkChamber's premise is that a privacy app should be **checkable**, not just believable:

- **Open source (GPLv3)** — every line is here.
- **No `INTERNET` permission in v1** — enforced by the release pipeline; check the manifest yourself.
- **Public [threat model](./docs/THREAT_MODEL.md)** — what we defend against *and what we honestly don't*. Marketing never promises more than that document.
- **Specified [container format](./docs/FORMAT.md)** with committed test vectors — auditable, and implementable by third parties.
- **Reproducible builds & F-Droid** are on the [roadmap](./ROADMAP.md), so the binary provably matches this source.

## 🏗️ Architecture at a glance

**Local-first, app-private only.** Vault content lives in encrypted `.dchm` containers inside app-private storage — never in shared MediaStore. Plaintext exists only in memory (or briefly in private `tmp/`, wiped on lock).

**Envelope encryption.** A random 256-bit master key is wrapped by (a) a hardware-keystore key gated on the device being unlocked (a per-use biometric prompt lands with the UI), (b) an Argon2id-derived key from your passphrase, and (c) your recovery key. The master key wraps per-file keys; files are chunked AES-256-GCM — enabling streaming, video seeking, resumable backup, and instant crypto-shredding. Full spec: [`docs/FORMAT.md`](./docs/FORMAT.md).

### Tech stack

| Concern           | Choice                                                                    |
|-------------------|---------------------------------------------------------------------------|
| Shared logic & UI | Kotlin Multiplatform + Compose Multiplatform                              |
| Cryptography      | `cryptography-kotlin` (AES-256-GCM) + Argon2id, behind our `CryptoEngine` |
| Key storage       | Android Keystore/StrongBox · iOS Keychain (device-unlocked)               |
| Local database    | SQLDelight (sensitive fields app-layer encrypted)                         |
| Camera            | CameraX · AVFoundation                                                    |
| Media playback    | Media3/ExoPlayer · AVPlayer (custom decrypting sources)                   |
| DI / Navigation   | Koin · Decompose                                                          |
| Networking        | **None in v1** (by design — ADR-004)                                      |

---

## 📁 Project structure

* [`/androidApp`](./androidApp) — Android entry point (versioning, signing, `foss`/`store` flavors).
* [`/iosApp`](./iosApp) — iOS entry point (Xcode project; any SwiftUI host code lives here).
* [`/shared`](./shared/src) — shared KMP code (`commonMain` + platform source sets).

> **Target architecture:** shared code moves into a **multi-module clean-architecture layout** — `core:crypto`, `core:storage`, `core:db`, feature modules with an `api`/`impl` split — with isolation enforced at compile time and by Konsist (including security rules: crypto only in `core:crypto`, shared-storage APIs only in `core:storage`). See [`docs/engineering/MODULES.md`](./docs/engineering/MODULES.md).

## ▶️ Running the apps

- **Android:** `./gradlew :androidApp:assembleFossDebug` (or use the IDE run widget)
- **iOS:** open [`/iosApp`](./iosApp) in Xcode and run.
- Resolved version: `./gradlew :androidApp:printVersion`

## ✅ Running tests

- **JVM/Android:** `./gradlew testAndroidHostTest`
- **iOS:** `./gradlew iosSimulatorArm64Test`

---

## 📚 Documentation

- [Roadmap](./ROADMAP.md) — phased plan, monetization & licensing model, non-goals.
- [Architecture](./docs/ARCHITECTURE.md) — modules, storage layout, camera/viewer design.
- [Threat Model](./docs/THREAT_MODEL.md) — what we protect against, and what we don't. **Normative for every product claim.**
- [`.dchm` Format Spec](./docs/FORMAT.md) — the encrypted container, key hierarchy, test vectors.
- [Design System](./docs/DESIGN.md) — colors, typography, shape, motion; the aperture signature and honesty rules for UI copy.
- [Engineering Guide](./docs/engineering/README.md) — modularization, testing, quality gates, Git workflow, CI/CD, releases. **Read this before contributing.**
- [Knowledgebase](./docs/knowledgebase) — a from-zero primer on the security concepts behind the app (encryption, AES/GCM, nonces, hashing, key derivation). New to crypto? Start here.
- [ADRs](./docs/ADR) — decisions and the reasoning behind them, including the refusals.
- [Security Policy](./SECURITY.md) — how to report vulnerabilities (please don't open public issues for those).

## 🤝 Contributing

Essentials — the [Engineering Guide](./docs/engineering/README.md) has the detail:

- **TDD** — tests first; coverage gated in CI (`core:crypto` ≈100%, domain 90%, overall 80%).
- **Conventional Commits, per commit** (`feat:`, `fix:`, `docs:`, …) — we merge, not squash; commits drive automated versioning.
- **Quality gates** — ktlint, detekt, Konsist (including security-invariant rules), Kover; locally via Lefthook, again in CI.
- **Security-sensitive PRs** (crypto, storage, format, session/lock) need the `security-review` label and must state which threat-model invariants they touch.
- **`main` is protected**; releases are tag-based and cut manually.

## ⚖️ License & trademark

Code is licensed under **[GPLv3](./LICENSE)**. The **DarkChamber name and logo are not part of the code license** — forks are welcome and must choose their own name. See `TRADEMARK.md`.

## 🕯️ A note of intention

Everyone has files that are simply *theirs* — and privacy shouldn't require trusting a stranger's promise. DarkChamber exists so that the promise is checkable: a sealed chamber, with the seal visible to anyone who cares to look.
