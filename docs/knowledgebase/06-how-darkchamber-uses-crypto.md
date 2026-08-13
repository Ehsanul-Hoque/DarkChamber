# 6 · How DarkChamber uses all this

*Part of the [DarkChamber Knowledgebase](./README.md). Educational, not normative — the binding spec
is [`FORMAT.md`](../FORMAT.md).*

Time to assemble pages 1–5 into the real system. Nothing new here — just the pieces you've met,
connected.

## The layers of keys ("envelope encryption")

DarkChamber never encrypts your files directly with your passphrase. It uses a small hierarchy so
that changing your password — or losing your phone — doesn't mean re-encrypting gigabytes.

```
        your passphrase ──Argon2id(salt)──▶ pass-key ─┐
        hardware keystore (biometric/PIN) ─────────────┤ each independently WRAPS (encrypts)
        recovery key ──────────────────────────────────┘        the one MasterKey (MK)
                                                                        │
                                            MasterKey (MK, 256-bit random)
                                                                        │  wraps
                                                    per-object DEK (256-bit random)   ← one per file/note/thumbnail
                                                                        │  encrypts
                                            the file's bytes as a chunked AES-256-GCM .dchm container
```

- **MasterKey (MK)** — one random 256-bit key per vault (page 1: random = unguessable). It's never
  stored in the clear; it's only ever kept **wrapped** (encrypted) three independent ways:
  - by a key in the **platform keystore**, gated on biometric/PIN (Android Keystore/StrongBox, iOS
    Secure Enclave/Keychain);
  - by a key **derived from your passphrase** with **Argon2id** (page 5);
  - by your **recovery key** (a random code you save — the only way back in on a brand-new device).
- **DEK (Data-Encryption Key)** — a *separate* random key for *each* object. MK encrypts (wraps) every
  DEK; a DEK encrypts one object's content.

Why the indirection pays off:

- **Change passphrase** → re-derive the pass-key and re-wrap the MK. Your files are untouched.
- **Unlock** → a keystore/passphrase/recovery path unwraps the MK into memory; MK unwraps the DEKs on
  demand.
- **Delete one item** → destroy its DEK and its ciphertext is permanently noise ("crypto-shredding" —
  instant, irreversible, no overwriting needed).
- **Panic wipe** → destroy the MK wraps; every DEK becomes unrecoverable at once.

## One object on disk: the `.dchm` container

Each object is encrypted with its DEK using everything from pages 2–4:

1. A **16-byte plaintext prelude** — magic `DCHM`, version, cipher id, header length, and the random
   `nonce_prefix` (page 3). Not encrypted, but **bound as AAD** so it can't be altered undetected.
2. An **encrypted header** (name, MIME, size, `sha256(plaintext)` from page 4, chunk size), sealed
   with AES-256-GCM.
3. The content in **1 MiB chunks**, each sealed with **AES-256-GCM** (page 2) using:
   - a **unique nonce** — `nonce_prefix ‖ chunk counter` (page 3: never reused);
   - **AAD** = `file_id ‖ chunk_index ‖ is_last` (page 3: defeats reordering, truncation, splicing);
   - a **16-byte tag** per chunk so any tampering fails "open" loudly.

Because chunks are independent, DarkChamber can **stream** multi-GB files and **seek** video (decrypt
just the chunk you need) — the payoff called out on page 2.

## What each concept bought us

| Concept (page)          | Where it shows up                                                   |
|-------------------------|---------------------------------------------------------------------|
| Symmetric AES-256 (1–2) | Encrypting every object's content and header                        |
| GCM / AEAD + tag (2–3)  | Tamper-evident chunks; a corrupted vault file is refused, not shown |
| Unique nonce (3)        | `nonce_prefix ‖ counter`, fresh DEK per object — no reuse, ever     |
| AAD binding (3)         | `file_id ‖ chunk_index ‖ is_last` stops reorder/truncate/splice     |
| SHA-256 (4)             | Whole-file integrity digest in the header                           |
| Argon2id KDF + salt (5) | Turning your passphrase into the key that wraps the MasterKey       |

## What actually exists today

This knowledgebase describes the whole design, but the code is being built in stages:

- **Built now** — the `.dchm` container **codec** (chunked AES-256-GCM encrypt/decrypt, header, unique
  nonces, AAD binding, SHA-256 digest) behind the swappable `CryptoEngine`, with committed
  [format test vectors](../format-test-vectors). This is the bottom layer of the diagram.
- **Coming next** — the **key hierarchy** (MasterKey, per-object DEK wrapping, Argon2id, recovery key)
  and the **platform keystore** integration (biometric/PIN-gated hardware wrap). Until those land,
  there is no unlock and nothing protects real files yet.

For the exact, normative details — byte layouts, the cipher registry, KDF parameters, versioning —
read [`FORMAT.md`](../FORMAT.md), and for what DarkChamber does and does **not** defend against, read
[`THREAT_MODEL.md`](../THREAT_MODEL.md).

> [!NOTE]
> **Key takeaways**
> - DarkChamber uses **envelope encryption**: one random **MasterKey**, wrapped by keystore +
>   Argon2id(passphrase) + recovery key; the MK wraps a **per-object DEK**; the DEK encrypts the file.
> - This makes password changes cheap, deletion instant (**crypto-shredding** a DEK), and panic wipe
>   a matter of destroying the MK wraps.
> - Each object is a **chunked AES-256-GCM `.dchm`** container using the exact nonce/AAD/hash ideas
>   from pages 2–4.
> - Today the **container codec exists**; the **key hierarchy and keystore are the next cycles**.

← Back to the [Knowledgebase index](./README.md) · Normative specs: [`FORMAT.md`](../FORMAT.md) ·
[`THREAT_MODEL.md`](../THREAT_MODEL.md)
