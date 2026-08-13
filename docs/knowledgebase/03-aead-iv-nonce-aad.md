# 3 · AEAD, IVs/nonces & AAD

*Part of the [DarkChamber Knowledgebase](./README.md). Educational, not normative.*

Page 2 ended on AES-256-GCM. This page unpacks the three terms you can't avoid once you use it:
**AEAD**, the **nonce**, and **AAD**. These are exactly the moving parts of DarkChamber's
[`.dchm` format](../FORMAT.md).

## AEAD: encryption that also detects tampering

**AEAD** = **A**uthenticated **E**ncryption with **A**ssociated **D**ata. It does two jobs at once:

1. **Encrypts** the plaintext (confidentiality).
2. **Authenticates** it — produces a 16-byte **tag** so decryption can verify nothing was altered
   (integrity + authenticity).

An AEAD "seal" takes `(key, nonce, plaintext, associated data)` and returns `ciphertext + tag`. An
AEAD "open" takes `(key, nonce, ciphertext + tag, associated data)` and either returns the plaintext
**or fails** because the tag didn't verify. There is no "decrypt but ignore the tag" — a mismatch
means *stop*. AES-256-GCM is an AEAD; so is ChaCha20-Poly1305.

DarkChamber's rule (see [`FORMAT.md`](../FORMAT.md) and the threat model): a failed tag is treated as
**possible tampering** and surfaced plainly — never retried, never partially shown.

## The nonce (a.k.a. IV): use once, never repeat

A **nonce** ("number used once"; also called an **IV**, initialization vector) is a value fed to the
cipher alongside the key so that encrypting the *same* plaintext twice gives *different* ciphertext.
For AES-GCM the nonce is 12 bytes.

The nonce is **not secret** — it's stored in the clear next to the ciphertext. It has exactly one
rule, and it is absolute:

> [!WARNING]
> **Never reuse a (key, nonce) pair.** Under GCM, encrypting two different messages with the same key
> *and* the same nonce is catastrophic: it can leak the plaintext and lets an attacker forge valid
> tags. "Unique," not "secret," is the property that matters.

How systems keep nonces unique:

- **Random** — pick a fresh random nonce each time. Safe as long as you don't generate so many that
  two collide by chance.
- **Counter** — start at 0 and increment for each message. Simple and collision-free *as long as the
  key is fresh*.

DarkChamber uses a **hybrid** that's collision-free by construction: each object gets a fresh random
8-byte `nonce_prefix`, and each 1 MiB chunk within it appends an incrementing counter. Prefix +
counter = a unique 12-byte nonce for every chunk. A separate fresh key (DEK) per object means nonces
never collide *across* objects either. (The header gets a reserved counter value so it can't collide
with any chunk — a neat detail in `FORMAT.md` §2.)

## AAD: bind context without encrypting it

**AAD** = **A**ssociated **D**ata: extra bytes the tag covers but that are **not encrypted**. AEAD
authenticates the AAD together with the ciphertext, so if either is altered, "open" fails.

Why encrypt nothing but still authenticate it? To **bind a ciphertext to its context** so it can't be
moved or reshuffled. If some fact is bound as AAD, an attacker can't take a validly-encrypted piece
and reuse it somewhere the AAD no longer matches — the tag check will reject it.

DarkChamber binds, as AAD on every chunk, `file_id ‖ chunk_index ‖ is_last_flag`. That single choice
defeats a whole class of attacks with no extra crypto:

- **Reordering** chunks → the `chunk_index` in the AAD no longer matches its position → tag fails.
- **Truncating** a file → the chunk marked `is_last` is missing → detected.
- **Splicing** a chunk from another file → its `file_id` doesn't match → tag fails.

The plaintext 16-byte prelude is likewise bound as the header's AAD, so its magic/version/nonce can't
be altered undetected.

## Putting the three together

One GCM chunk in DarkChamber:

```
seal( key = DEK,
      nonce = nonce_prefix ‖ chunk_counter,          # unique, never reused
      associated data = file_id ‖ chunk_index ‖ is_last,   # bound, not encrypted
      plaintext = this 1 MiB slice )
   → ciphertext ‖ 16-byte tag
```

That's AEAD, a unique nonce, and meaningful AAD working as a unit. It's the literal shape of the
[`.dchm`](../FORMAT.md) container.

> [!NOTE]
> **Key takeaways**
> - **AEAD** encrypts *and* authenticates; "open" **fails** on a bad tag rather than returning
>   corrupted data. AES-256-GCM is an AEAD.
> - A **nonce/IV** must be **unique per key — never reused** (catastrophic under GCM). It's public,
>   just non-repeating. DarkChamber uses random prefix + per-chunk counter.
> - **AAD** is authenticated-but-not-encrypted context. Binding `file_id ‖ chunk_index ‖ is_last`
>   defeats reordering, truncation, and cross-file splicing for free.

Next → [Hashing](./04-hashing.md)
