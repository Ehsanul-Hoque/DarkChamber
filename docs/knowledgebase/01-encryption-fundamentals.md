# 1 · Encryption fundamentals

*Part of the [DarkChamber Knowledgebase](./README.md). Educational, not normative.*

## The one-sentence idea

**Encryption turns readable data into scrambled data that only someone with the right key can turn
back.** That's it. Everything else is detail about *how* to do that safely.

- **Plaintext** — the readable data (a photo, a note, a video).
- **Ciphertext** — the scrambled output. To anyone without the key it should look like random noise.
- **Key** — a secret value (for us, a 256-bit random number — 32 bytes). The key is what separates
  "can read it" from "can't".
- **Encrypt** — plaintext + key → ciphertext. **Decrypt** — ciphertext + key → plaintext.

```
  plaintext ──(encrypt with key)──▶ ciphertext ──(decrypt with key)──▶ plaintext
```

## The key is the secret — not the method

A foundational rule, **Kerckhoffs's principle**: a system should stay secure *even if everyone knows
exactly how it works* — only the key is secret. This is why DarkChamber publishes its whole design
and container format. "Secret algorithms" are a red flag: security that depends on nobody looking is
not security. Security that survives full public scrutiny is.

This is also why we can hand you this knowledgebase and the [`FORMAT.md`](../FORMAT.md) spec without
weakening anything. The strength lives entirely in keys you hold.

## Three different goals (they are not the same)

People say "encryption" loosely, but real security cares about three separate properties:

| Goal                | Question it answers                    | Example failure                                                                                            |
|---------------------|----------------------------------------|------------------------------------------------------------------------------------------------------------|
| **Confidentiality** | Can an attacker *read* it?             | Someone copies your vault file and views your photos.                                                      |
| **Integrity**       | Has it been *changed*?                 | Someone flips a few bytes and your file decrypts to garbage — or worse, to *different* valid-looking data. |
| **Authenticity**    | Is it really *from who/what I expect*? | Someone swaps in a chunk from a different file and you don't notice.                                       |

Plain "encryption" (scrambling) only buys **confidentiality**. Getting integrity and authenticity
too requires a cipher used in an *authenticated* mode — the subject of pages 2 and 3. DarkChamber
always uses authenticated encryption, so a tampered container is *detected and rejected*, never
silently shown to you.

## Two families of encryption

- **Symmetric** — the *same* key encrypts and decrypts. Fast, ideal for bulk data (files, video).
  AES (page 2) is symmetric. DarkChamber encrypts your files symmetrically.
- **Asymmetric** (public-key) — a *pair* of keys: a public one to encrypt, a private one to decrypt.
  Slower; used for sharing a secret with someone you haven't met, and for signatures. DarkChamber is
  local-first and single-user, so it leans on symmetric crypto, with a touch of asymmetric only
  inside the platform keystore (page 6).

Rule of thumb: **symmetric = same secret on both sides; asymmetric = a public/private pair.**

## What is *not* encryption

These get confused with encryption constantly. None of them keep a secret:

- **Encoding** (Base64, hex, URL-encoding) — a reversible *reformatting* with no key. Anyone can undo
  it. It's for moving data around, not hiding it.
- **Compression** (zip, gzip) — makes data smaller; no key, fully reversible by anyone.
- **Hashing** (page 4) — a *one-way* fingerprint. You can't turn a hash back into the data, so it's
  the opposite of encryption's round trip. Great for integrity checks, useless for storing something
  you need to read back.

If there's no key, it isn't encryption.

## Where do keys come from?

A key is only as good as its unpredictability. DarkChamber's keys come from two places:

- **Randomness** — a cryptographically secure random generator produces the actual data-encrypting
  keys (32 fully random bytes). Unpredictable by construction.
- **Your passphrase** — a human password is *not* random enough to be a key directly, so it's run
  through a deliberately slow **key-derivation function** (page 5) that turns it into one.

> [!NOTE]
> **Key takeaways**
> - Encryption = plaintext + key → ciphertext, and back again with the same (symmetric) key.
> - The **key** is the only secret; the method is public (Kerckhoffs). Distrust secret algorithms.
> - Three distinct goals: **confidentiality** (unreadable), **integrity** (unchanged),
>   **authenticity** (genuinely from the expected source). Plain scrambling only gives the first.
> - Encoding, compression, and hashing are **not** encryption — no key, no secret.

Next → [AES & block-cipher modes](./02-aes-and-block-cipher-modes.md)
