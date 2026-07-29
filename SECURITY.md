# Security Policy

DarkChamber is a security product; we treat vulnerability reports as the most important issues the project receives. Thank you for taking the time to report responsibly.

## Supported versions

Only the **latest released version** on Google Play, the App Store, F-Droid, and GitHub Releases receives security fixes. Pre-release builds (`main`) are supported on a best-effort basis. We do not backport fixes to older versions — DarkChamber updates are small and free, so please stay current.

| Version              | Supported   |
|----------------------|-------------|
| Latest release       | ✅           |
| `main` (pre-release) | Best effort |
| Older releases       | ❌           |

## Reporting a vulnerability

**Please do NOT open a public GitHub issue for security vulnerabilities.**

Preferred channel, in order:

1. **GitHub private vulnerability reporting:** use the ["Report a vulnerability"](../../security/advisories/new) button on this repository (Security tab → Advisories). This keeps the report private, threads discussion, and lets us publish a coordinated advisory with credit to you.
2. **Email:** `security@darkchamber.app` <!-- TODO: create this mailbox before launch -->
   Encrypt sensitive reports with our PGP key: <!-- TODO: generate, publish key + fingerprint here and on the website -->
   ```
   Fingerprint: XXXX XXXX XXXX XXXX XXXX  XXXX XXXX XXXX XXXX XXXX
   ```

Please include, where possible:

- A description of the vulnerability and its impact (what an attacker gains).
- Steps to reproduce, proof-of-concept code, or a failing test vector.
- Affected version(s), platform (Android/iOS), and device/OS details.
- Whether the issue is in DarkChamber's code, a dependency, or the `.dchm` format itself (see [`docs/FORMAT.md`](docs/FORMAT.md)).
- How you'd like to be credited (name/handle/anonymous).

## What to expect from us

| Stage                                   | Target time                                                |
|-----------------------------------------|------------------------------------------------------------|
| Acknowledgement of your report          | **within 72 hours**                                        |
| Initial assessment (severity, validity) | within 7 days                                              |
| Status updates while we work on a fix   | at least every 14 days                                     |
| Fix released                            | as fast as severity demands; criticals are drop-everything |
| Coordinated public disclosure           | **within 90 days** of report, or sooner once a fix ships   |

We follow **coordinated disclosure with a 90-day deadline**: we ask that you give us up to 90 days before publishing details; in return we commit to fixing promptly, keeping you informed, publishing a GitHub Security Advisory with a CVE where applicable, and crediting you (unless you prefer anonymity). If we need more than 90 days for an exceptional reason, we'll discuss it with you openly — the deadline is ours to earn an extension on, not to demand one.

If active exploitation in the wild is observed, we may accelerate disclosure and shorten these timelines to protect users.

## Scope

**In scope:**

- The DarkChamber apps (Android, iOS) and all first-party modules in this repository.
- The `.dchm` container format and key hierarchy as specified in [`docs/FORMAT.md`](docs/FORMAT.md) — including cryptographic design flaws, not just implementation bugs.
- Violations of the security invariants in [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) (e.g., plaintext reaching shared storage, plaintext thumbnails, temp files surviving lock). **These are P0 even without a working exploit.**
- The Phase 3 backup/sync design and implementation.
- Supply-chain issues specific to our build (e.g., our CI producing artifacts that don't match source).

**Out of scope** (see [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md) for reasoning):

- Attacks requiring a compromised/rooted OS with malware running while the vault is unlocked.
- Coerced unlock scenarios beyond the documented best-effort duress features.
- Recovery of pre-import originals from flash storage (documented limitation, disclosed in-app).
- Brute-forcing deliberately weak user PINs where hardware protections are functioning as designed.
- Vulnerabilities in the OS, in Google Drive/iCloud, or in third-party dependencies without a DarkChamber-specific impact (please report those upstream — we're happy to help coordinate).
- Social engineering of users or maintainers; physical attacks on unlocked devices.

## Safe harbor

We will not pursue legal action against, or report to law enforcement, security researchers who: act in good faith, avoid privacy violations and data destruction affecting real users, test only against their own devices and vaults, and follow this policy. Good-faith research is welcome here.

## Bounties & recognition

DarkChamber is an independent open-source project and does not currently run a paid bug bounty. What we can offer: public credit in the advisory, release notes, and a `HALL_OF_FAME.md`; and our genuine gratitude. If the project's funding grows (see the roadmap's monetization plan), a modest bounty program is on the wish list.

## Verifying official builds

Reproducible builds are a project goal (see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §14). Once shipped, release signatures and instructions for verifying that store binaries match this source tree will be published here and on the website. Report any mismatch immediately via the channels above — a binary/source mismatch is treated as a critical incident.
