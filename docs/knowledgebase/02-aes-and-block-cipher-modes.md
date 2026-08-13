# 2 · AES & block-cipher modes

*Part of the [DarkChamber Knowledgebase](./README.md). Educational, not normative.*

Page 1 said encryption is "plaintext + key → ciphertext." Now: *which* cipher, and — just as
important — *how* you apply it.

## AES: the workhorse cipher

**AES** (Advanced Encryption Standard) is the symmetric cipher the world runs on: banks, HTTPS, disk
encryption, DarkChamber. It's been public and heavily attacked since 2001 and remains unbroken — a
perfect illustration of Kerckhoffs's principle from page 1.

AES is a **block cipher**: it encrypts a fixed-size chunk of exactly **16 bytes (128 bits)** at a
time — no more, no less. The key can be 128, 192, or **256 bits**. DarkChamber uses **AES-256**
(256-bit keys). Bigger key = larger space of possible keys = more infeasible to brute-force. AES-256
is the common "maximum strength" choice.

> [!NOTE]
> "AES-256" describes the **key size (256 bits)**, not the block size. The block is always 128 bits.

## The problem: files are bigger than one block

A block cipher only knows how to transform 16 bytes. A photo is millions of bytes. So you need a
rule for applying the cipher over and over across a long message. That rule is the **mode of
operation**, and choosing it wrong destroys security *even though the underlying AES is perfect*.

### ECB — the wrong way (never use it)

The naive mode: chop the data into 16-byte blocks and encrypt each **independently** with the key.

The fatal flaw: **identical plaintext blocks produce identical ciphertext blocks.** Patterns in the
data survive into the ciphertext. The famous demo is the "ECB penguin" — encrypting a bitmap of
Tux with ECB leaves the penguin's outline plainly visible in the ciphertext. ECB leaks structure.
Don't use it. (It exists in libraries as a building block; that's not permission to pick it.)

### CBC, CTR — chaining and counters

Better modes make each block's encryption depend on something that varies, so identical plaintext no
longer yields identical ciphertext:

- **CBC** (Cipher Block Chaining) — each block is mixed with the *previous* ciphertext block before
  encryption, so repetition is hidden. Historically common; fiddly to use safely (padding, ordering)
  and, on its own, provides **no integrity** — it can't tell you the ciphertext was tampered with.
- **CTR** (Counter) — turns the block cipher into a keystream generator: encrypt a running counter
  and XOR the result with the plaintext. Streamable and allows random access (you can jump to any
  position). But CTR alone *also* has no integrity.

The recurring gap: these classic modes give confidentiality but **not** integrity or authenticity —
the other two goals from page 1. That's what the next mode fixes.

### GCM — the mode DarkChamber uses

**GCM** (Galois/Counter Mode) is CTR *plus* a built-in authentication check. It gives you:

- **Confidentiality** — CTR-style keystream, so it's streamable and supports random access.
- **Integrity + authenticity** — it computes a short **authentication tag** (16 bytes) over the
  ciphertext. On decryption, if even one bit was changed, the tag doesn't match and decryption
  **fails loudly** instead of returning corrupted data.

This combination — encrypt *and* authenticate together — is called **AEAD**, the subject of page 3.
DarkChamber uses **AES-256-GCM** for every encrypted object.

Why it fits DarkChamber specifically:

- **Streamable** → encrypt/decrypt a multi-GB video without loading it all into memory.
- **Random access** → jump straight to the chunk you need, which is how video **seeking** works.
- **Authenticated** → a tampered or corrupted vault file is detected and refused, never shown to you.

GCM has one sharp edge — a number called the **nonce** that must never repeat for a given key.
Getting that right is the whole of page 3.

> [!NOTE]
> **Key takeaways**
> - **AES** is a symmetric **block cipher** working on 128-bit blocks; **AES-256** = 256-bit key.
> - A **mode of operation** applies the cipher across a long message. The mode matters as much as
>   the cipher.
> - **ECB leaks patterns — never use it.** CBC/CTR hide patterns but give **no integrity** by themselves.
> - **GCM = CTR + an authentication tag**: confidentiality *and* integrity/authenticity, streamable,
>   random-access. DarkChamber uses **AES-256-GCM**.

Next → [AEAD, IVs/nonces & AAD](./03-aead-iv-nonce-aad.md)
