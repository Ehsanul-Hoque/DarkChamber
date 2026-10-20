# DarkChamber 📷🔒

> Long before photography, a *camera* was just a chamber: a dark, sealed room where images formed, seen by no one outside. DarkChamber brings that idea back — **a private camera and an encrypted vault in one. What enters the chamber never leaves it.**

A Kotlin Multiplatform app for **Android** and **iOS** that encrypts, stores, and views your private files — photos, videos, audio, PDFs, notes, and documents — with modern, verifiable cryptography. Photos taken with the built-in camera never touch shared storage, so cloud services (Google Photos included) never see them.

**Free and open source (GPLv3). Local-first. No ads, no trackers, no accounts — and v1 ships with no `INTERNET` permission, so you can verify in the manifest that your files cannot leave your device.**

> ⚠️ **Status: pre-release.** The encrypted-vault core is **built and verified**, and the vault
> itself now has a usable face on it — but **nothing here is ready to protect real files yet.**
> What exists, on both Android and iOS:
>
> - the **`.dchm` container format** (chunked AES-256-GCM behind a swappable `CryptoEngine`) and the
>   portable **key hierarchy** (MasterKey/per-object DEK, Argon2id passphrase + PBKDF2 fallback + HKDF
>   recovery, a hardware-backed key slot behind a `SecureKeyStore` seam), checked against committed
>   [format & KeyStore test vectors](./docs/format-test-vectors);
> - the **storage layer** (atomic app-private stores, backup denied) and the encrypted **SQLDelight
>   catalog**;
> - **streaming** import + large-file (2 GB video) **stream-decrypt** in bounded RAM;
> - the **session gate** with cryptographic per-use **biometric unlock**, a passphrase fallback, and a
>   lock lifecycle that tears the session down on lock and on backgrounding;
> - the **vault screen** — a grid of what you have put in, newest first — and **batch import** from
>   the file picker or the photo picker, with progress you can watch and stop part-way through;
> - **encrypted previews**, generated as a file is imported and sealed under their own key. No
>   decrypted image is ever written to storage, and the in-memory cache is emptied when you lock;
> - the option to **delete the original** after importing it, where the system allows it, behind a
>   disclosure you have to answer that says what deleting on flash storage can and cannot promise;
> - **albums** — group what you have put in, see what is in no album, move a selection in or out, and
>   import straight into the album you are looking at. An album is a label, not a container: deleting
>   one keeps every file in it, and an item is in one album at a time, so filing it moves it. Albums
>   organise — they are not a second lock, and nothing in the app pretends otherwise;
> - **browsing** — four orders (newest, oldest, A–Z, Z–A) and a search over names, plus a line in the
>   bar saying which listing you are looking at. Both run on the rows already on screen, because a
>   name lives inside the encrypted metadata and no database can sort or match what it cannot open;
>   changing the order or typing never re-decrypts a row. The search covers names on the current
>   listing and says so, an item whose name will not open is counted out rather than quietly dropped,
>   and an empty grid names which of the five reasons it is empty;
> - **a private camera, for photos** — opened from any listing, it files each shot where an import
>   from there would and shows that listing's count live. A photo goes from the camera into memory
>   and from memory into the vault's encryption: no file, nothing in the system photo library, no
>   location attached;
> - **nothing handed to the rest of the phone** — screenshots and screen recording are blocked, and
>   every window the app opens, each dialog included, keeps itself out of the platform's autofill and
>   content-capture structures. That channel is **not** covered by the screenshot flag: it was
>   measured sending a vault text field to another process, which is why a password manager cannot
>   fill the passphrase here.
>
> What's left before the vault is usable: the camera's **video** and the **in-app viewers** — tapping
> an item, or the camera's last-shot tile, is deliberately inert until those land — then export with
> metadata scrubbing and the settings screen. The documents under [`docs/`](./docs) describe the target design.

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

**Envelope encryption.** A random 256-bit master key is wrapped by (a) a hardware-keystore key gated on a per-use biometric prompt (Face/Touch ID bound cryptographically to the key), (b) an Argon2id-derived key from your passphrase, and (c) your recovery key. The master key wraps per-file keys; files are chunked AES-256-GCM — enabling streaming, video seeking, resumable backup, and instant crypto-shredding. Full spec: [`docs/FORMAT.md`](./docs/FORMAT.md).

### Tech stack

| Concern           | Choice                                                                    |
|-------------------|---------------------------------------------------------------------------|
| Shared logic & UI | Kotlin Multiplatform + Compose Multiplatform                              |
| Cryptography      | `cryptography-kotlin` (AES-256-GCM) + Argon2id, behind our `CryptoEngine` |
| Key storage       | Android Keystore/StrongBox · iOS Keychain (per-use biometric)             |
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
