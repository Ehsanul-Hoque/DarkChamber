# 4 · Hashing

*Part of the [DarkChamber Knowledgebase](./README.md). Educational, not normative.*

Page 1 warned that hashing is **not** encryption. So what *is* it, and why does DarkChamber use it?

## A hash is a one-way fingerprint

A **cryptographic hash function** takes any input — one byte or a 4 GB video — and produces a
fixed-size **digest** (for SHA-256, always 32 bytes). Same input always gives the same digest;
different inputs give wildly different digests.

```
  "hello"            ─▶ 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
  "hello."           ─▶ 725d4b...  (completely different — one added byte)
  a 4 GB movie file  ─▶ 32 bytes
```

The word "one-way" is the point: it's easy to go input → digest, and **infeasible** to go digest →
input. A hash is a fingerprint, not a container — you can't get the data back out of it.

## The properties that make a hash "cryptographic"

1. **Deterministic** — same input, same digest, always. (That's what lets you compare.)
2. **Fast** — cheap to compute over lots of data. *(Keep this in mind — for passwords, page 5, fast
   is exactly wrong.)*
3. **Preimage-resistant (one-way)** — given a digest, you can't find an input that produces it.
4. **Collision-resistant** — you can't find two *different* inputs with the *same* digest.
5. **Avalanche effect** — flip one bit of input and about half the output bits flip. No partial
   relationship between similar inputs survives.

When a hash function's collision resistance is broken, the function is considered dead for security.

## The SHA family (and MD5)

| Function    | Digest size | Status                                                | Use it?                             |
|-------------|-------------|-------------------------------------------------------|-------------------------------------|
| **MD5**     | 128-bit     | **Broken** — collisions are trivial                   | Never (for security)                |
| **SHA-1**   | 160-bit     | **Broken** — practical collisions demonstrated (2017) | Never (for security)                |
| **SHA-256** | 256-bit     | Strong, current standard                              | ✅ Yes — DarkChamber uses this       |
| **SHA-512** | 512-bit     | Strong (SHA-2 family, wider)                          | Fine                                |
| **SHA-3**   | 256/512-bit | Strong, different internal design                     | Fine (a structural backup to SHA-2) |

"SHA-2" is the family; **SHA-256** is its 256-bit member and today's default. **SHA-3** is a separate,
newer design kept in reserve so the world isn't reliant on one construction. **MD5 and SHA-1 are
broken** — you'll still see them for non-security checksums, but never for anything a security
decision depends on.

## What hashing is *for* — and what it is *not*

**Good uses:**

- **Integrity / verification** — store a file's hash; recompute later; if the digests match, the file
  is byte-for-byte intact. DarkChamber records `sha256(plaintext)` in each object's header as a
  whole-file integrity cross-check (on top of the per-chunk GCM tags from page 3).
- **Fingerprinting / dedup / addressing** — a short stand-in for a big blob, e.g. to notice two files
  are identical without comparing them byte by byte.
- **Building block** — hashes are the engine inside signatures, HMACs, and the password functions on
  page 5.

**Not for:**

- **Encryption** — a hash is one-way; there's nothing to "decrypt." If you need the data back, you
  need encryption (pages 1–3), not a hash.
- **Storing passwords directly** — a plain, *fast* hash of a password is dangerously weak against
  guessing attacks. That needs a *deliberately slow* function — the whole point of page 5.

> [!NOTE]
> **Key takeaways**
> - A hash is a **one-way, fixed-size fingerprint**; you cannot recover the input from it.
> - Key properties: deterministic, one-way (preimage-resistant), collision-resistant, avalanche.
> - **MD5 and SHA-1 are broken.** Use **SHA-256** (or SHA-3). DarkChamber uses SHA-256.
> - Hashing is for **integrity and fingerprinting**, *not* encryption and *not* raw password storage.

Next → [Passwords & key derivation](./05-passwords-and-key-derivation.md)
