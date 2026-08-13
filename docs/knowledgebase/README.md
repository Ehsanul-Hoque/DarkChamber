# DarkChamber Knowledgebase — security from zero

A from-scratch on-ramp to the ideas DarkChamber is built on. If words like *AES*, *nonce*, *AEAD*,
*hash*, or *key derivation* are unfamiliar, start here — you'll be able to read the real
[`FORMAT.md`](../FORMAT.md) and [`THREAT_MODEL.md`](../THREAT_MODEL.md) afterwards and understand
*why* the app is shaped the way it is.

## Who this is for

Contributors and curious users with no cryptography background. No math beyond counting is assumed.
The goal is a correct mental model, not a course — each page ends with a **key takeaways** box.

> [!IMPORTANT]
> **This folder is educational, not normative.** When it simplifies, it says so. The binding
> sources of truth are [`FORMAT.md`](../FORMAT.md) (the `.dchm` container) and
> [`THREAT_MODEL.md`](../THREAT_MODEL.md) (what DarkChamber does and does not defend against). If a
> primer here ever disagrees with those, they win — please open an issue.

## Learning path

Read in order; each builds on the last.

1. [Encryption fundamentals](./01-encryption-fundamentals.md) — plaintext, ciphertext, keys, and the
   three things security aims for: confidentiality, integrity, authenticity.
2. [AES & block-cipher modes](./02-aes-and-block-cipher-modes.md) — what AES is, and why *how* you
   use a cipher (its "mode") matters as much as the cipher itself.
3. [AEAD, IVs/nonces & AAD](./03-aead-iv-nonce-aad.md) — authenticated encryption, the numbers you
   must never reuse, and binding extra context to a ciphertext.
4. [Hashing](./04-hashing.md) — one-way fingerprints: SHA-256 and friends, what they're for and what
   they are not.
5. [Passwords & key derivation](./05-passwords-and-key-derivation.md) — turning a human passphrase
   into a strong key, and why ordinary hashing is the wrong tool for it.
6. [How DarkChamber uses all this](./06-how-darkchamber-uses-crypto.md) — the concepts assembled into
   the real `.dchm` container and key hierarchy.

## How to use it

Skim once for the shape, then keep it open beside `FORMAT.md` when you work on `core:crypto`. Every
page links the next; the last page maps each concept to a specific part of the codebase and spec.
