# DarkChamber — Threat Model

> This document states plainly what DarkChamber protects against and what it does not. It is a normative part of the project: marketing claims, store listings, and in-app copy must never promise more than this document does. Companion docs: [`ARCHITECTURE.md`](./ARCHITECTURE.md) (how the protections are implemented) and [`FORMAT.md`](./FORMAT.md) (the encrypted container specification).

## Assets we protect

- File contents (photos, videos, audio, PDFs, documents) stored in the vault
- File metadata: original filenames, MIME types, capture data, album names
- Encrypted notes
- Thumbnails and previews
- The vault catalog (what exists, how it's organized)

## In scope — threats we defend against

1. **Other apps or cloud backup services reading vault files.** Vault content lives in app-private storage and never enters shared MediaStore, so scanners (including Google Photos backup) never see it. Content is additionally encrypted at rest.
2. **A device thief or snooping person who does not know the PIN/passphrase.** Master-key material is wrapped by hardware-backed keys (Android Keystore/StrongBox, iOS Secure Enclave) gated on biometric/PIN, with rate-limited unlock attempts. Key material is unrecoverable without successful authentication.
3. **Casual and forensic inspection of a locked or powered-off device.** Only ciphertext exists on disk: file contents, filenames, metadata, and thumbnails are all encrypted. A full filesystem image without the user's PIN or recovery key yields no plaintext.
4. **Root-level extraction of stored data.** Even with root access to the app's private directory, an attacker obtains ciphertext plus a wrapped master key. Hardware-bound wraps cannot be exported from the secure element; the password wrap requires brute-forcing the passphrase through Argon2id (memory-hard, deliberately slow).
5. **Cloud provider inspection (Phase 3 backup).** Providers store ciphertext with encrypted names and metadata; the backup design is zero-knowledge and we operate no servers.
6. **Shoulder-surfing and incidental leaks.** Screenshot blocking (`FLAG_SECURE`) on Android, app-switcher snapshot obscuring on both platforms, optional tap-to-reveal for sensitive lists.

## Out of scope — threats we do not defend against (and say so)

1. **A compromised or rooted OS with malware running at runtime.** Malware with root/system privileges can read process memory while the vault is unlocked and capture decrypted content in that window. No application on either platform can defend against a hostile OS; we do not claim to.
2. **Coerced unlock.** If a user is forced to authenticate, the vault opens. Duress features (decoy PIN, panic wipe) provide partial, best-effort mitigation only, and we describe them as such.
3. **Recovery of pre-import originals from flash storage.** When a user imports an existing file and deletes the original, flash wear-leveling and garbage collection may retain recoverable copies outside our control. We crypto-shred what we control and disclose this limit at the point of deletion. (See the shredder design in `ARCHITECTURE.md` §10.)
4. **Screenshot capture on iOS.** iOS does not allow apps to block screenshots; we can only detect and warn. Android blocking via `FLAG_SECURE` is best-effort against OS-level capture.
5. **Traffic analysis of backups (Phase 3).** Upload timing and ciphertext sizes are observable to the network and the storage provider. Padding/obfuscation may be considered later but is not promised.
6. **Weak user secrets.** A 4-digit PIN protected only by Argon2id (i.e., if hardware key protections are somehow bypassed) is brute-forceable. Onboarding nudges users toward 6+ digits or a passphrase; rate limiting and hardware gating are the practical defenses.

## Security invariants (enforced in code review)

- Plaintext exists only in memory or in app-private `tmp/`, never in shared storage.
- Any plaintext temp file is deleted immediately after use and on vault lock.
- No plaintext thumbnails outside the encrypted thumbnail store.
- Raw MK/DEKs are zeroized best-effort after use (GC-copy limits acknowledged).
- A violation of any invariant is a P0 security bug, regardless of user impact observed.

## Reporting

Vulnerabilities: see [`SECURITY.md`](../SECURITY.md) at the repository root for the disclosure process and timelines.
