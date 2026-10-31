# DarkChamber — `.dchm` Container Format Specification

> **Normative document.** This spec, together with the committed test vectors, is the source of truth for the DarkChamber encrypted container. Implementations (the app itself, future CLI tools, third-party decryptors) are verified against it. Changes require a format-version bump and a migration/compatibility plan — old vaults must always open. Companion docs: [`ARCHITECTURE.md`](./ARCHITECTURE.md), [`THREAT_MODEL.md`](./THREAT_MODEL.md).

**Format version:** 1 (draft — frozen at v1.0 release)

## 1. Design goals

- **Authenticated encryption** of arbitrary-size files with per-chunk integrity (AES-256-GCM).
- **Streaming**: constant-memory encryption/decryption of multi-GB files.
- **Random access**: decrypt any chunk independently (video seeking).
- **Resumability**: chunk-level upload/download for cloud backup.
- **Tamper evidence**: reordering, truncation, or splicing of chunks must fail authentication.

## 2. Container layout

Every encrypted object — media file, document, note body, thumbnail — uses the same container:

```
[ MAGIC "DCHM" (4 bytes) | u8 version=1 | u8 cipher_id | u16 header_len | nonce_prefix (8 bytes) ]
[ encrypted header ]
[ chunk 0 ][ chunk 1 ] … [ chunk N ]
```

All multi-byte integers are little-endian.

### 2.1 Prelude (plaintext, 16 bytes)

| Field        | Size | Value                                                         |
|--------------|------|---------------------------------------------------------------|
| magic        | 4    | ASCII `DCHM`                                                  |
| version      | 1    | `0x01`                                                        |
| cipher_id    | 1    | see §5                                                        |
| header_len   | 2    | byte length of the encrypted header block                     |
| nonce_prefix | 8    | random per object; the GCM nonce prefix for header and chunks |

`nonce_prefix` lives in the **plaintext** prelude (nonces are not secret, only unique) so the header
nonce can be derived before the header is decrypted — see §2.3. The whole 16-byte prelude is bound as
the header's AAD.

### 2.2 Encrypted header

Serialized (kotlinx.serialization, CBOR) then encrypted with the object's DEK; the 16-byte plaintext prelude is bound as AAD so it cannot be altered without detection.

```
header = AES-256-GCM(
    key   = DEK,
    nonce = nonce_prefix (8 bytes) ‖ u32 0xFFFFFFFF,   # reserved header counter (see §2.3)
    aad   = the 16-byte plaintext prelude,
    data  = CBOR(header fields)
) ‖ 16-byte GCM tag
```

Fields: `original_name`, `mime`, `plaintext_size`, `created_at`, `sha256(plaintext)`, `chunk_size`.
(`nonce_prefix` is **not** a header field — it lives in the plaintext prelude, §2.1.)

`chunk_size` default: 1 MiB (2²⁰ bytes). The final chunk may be shorter.

### 2.3 Chunks

```
chunk i = AES-256-GCM(
    key   = DEK,
    nonce = nonce_prefix (8 bytes) ‖ u32 chunk_index,
    aad   = file_id ‖ u32 chunk_index ‖ u8 is_last_flag,
    data  = plaintext[i·CS … min((i+1)·CS, size))
) ‖ 16-byte GCM tag
```

- **Chunk count.** `N = ceil(plaintext_size / chunk_size)` for a non-empty object; an **empty object**
  (`plaintext_size == 0`) encodes as exactly **one** chunk (index 0, `is_last_flag = 1`, empty
  plaintext, 16-byte tag only) so that even an empty file has one authenticated chunk binding
  `is_last` — a truncated-to-header stream is therefore distinguishable from a real empty file.
  Chunk indices run `0 … N-1` and `N ≤ 2³²-1`; the counter value `0xFFFFFFFF` is **reserved** for the
  header nonce and is never a chunk index.
- **Nonce uniqueness:** fresh random `nonce_prefix` per object + counter per chunk → unique nonces
  within an object; the header uses the same `nonce_prefix` with the reserved counter `0xFFFFFFFF`, so
  header and chunk nonces are **disjoint by construction** under the shared DEK; a fresh random DEK per
  object → no cross-object reuse. Nonce reuse under GCM is catastrophic; all three mechanisms are mandatory.
- **AAD binding** of `file_id`, `chunk_index`, and `is_last_flag` makes chunk reordering, truncation, and cross-file splicing fail authentication.
- Decryption MUST verify every chunk's tag before releasing its plaintext, MUST verify `is_last_flag` on the final chunk, and SHOULD verify the header's `sha256(plaintext)` on full-file reads.
- **Reading by position** (the random access of §1). A reader that serves byte ranges opens the
  prelude and the header, checks that the container is exactly
  `16 + header_len + Σ (stored length of chunk i)` bytes long — each chunk's plaintext plus its 16-byte
  tag — and authenticates the final chunk, with `is_last_flag = 1`, before it serves any read. It then
  decrypts chunk `⌊offset / chunk_size⌋` for each read. Every chunk it returns has passed its tag with
  `file_id`, `chunk_index` and `is_last_flag` bound, so reordering, truncation, splicing and a container
  opened under the wrong `file_id` still fail — the last two at the open, since the header carries no
  `file_id` and only a chunk can prove it. What such a reader **cannot** verify is `sha256(plaintext)`,
  which needs the whole plaintext: a damaged chunk it never reads goes unnoticed **by that reader**,
  while every chunk it does return is authentic. The digest is defence in depth against a chunking bug;
  against anyone without the DEK, the per-chunk tags already decide.

## 3. Key hierarchy (what a decryptor needs)

This section specifies enough of the key model to decrypt a vault or backup given user secrets; implementation details of hardware storage live in `ARCHITECTURE.md` §3.

```
MasterKey (MK): 256-bit random, generated at vault creation.

Wraps of MK:
  # KDF wraps — each a WrapEntry in the KeyStore blob (§3.1):
  wrap_pw       = AES-256-GCM(key = KDF_pw(passphrase, params),  data = MK)   # slot PASSPHRASE
  wrap_recovery = AES-256-GCM(key = HKDF-SHA256(recovery_key),   data = MK)   # slot RECOVERY
  # Hardware wrap — an OPAQUE platform blob, NOT a WrapEntry (§3.1):
  wrap_hw       = SecureKeyStore.wrap(MK)   # slot HARDWARE; device-local only, never in backups

Per-object: dek_wrapped = AES-256-GCM(key = MK, data = DEK), stored in the catalog
            (and in the backup manifest for cloud restore).
```

- **Passphrase KDF (`KDF_pw`).** A memory-hard KDF whose id + parameters are stored beside the
  wrap (§3.1) so they can be raised over time without a format break. **The default is Argon2id**
  (`kdf_id = ARGON2ID`, version **0x13** / RFC 9106, params `m` = 64 MiB, `t` = 3, `p` = 2, salt —
  tuned to ~100–250 ms on mid-range devices). **PBKDF2-HMAC-SHA256** (`kdf_id = PBKDF2_SHA256`,
  ≥ 600 000 iterations) remains a fully supported fallback; a reader must open a wrap under whichever
  `kdf_id` it stores. The Argon2 version (0x13) is fixed by this spec — not a stored field.
- **Recovery key:** 256-bit random — already full entropy, so its slot uses **HKDF-SHA256** (a fast
  extract-and-expand, *not* a stretching KDF; label `darkchamber:recovery-wrap:v1`), never Argon2id.
  Presented to the user as grouped **Crockford base32 with a checksum** (BIP39-style words may be
  added later as a presentation option; the raw 256 bits are the source of truth). `wrap_recovery`
  MUST be included in backup manifests — it is the only unlock path on a fresh device.
- **Hardware slot (`wrap_hw`).** An **opaque, platform-produced blob** — the MK wrapped by a
  hardware-held key that never leaves the device's secure hardware (Android Keystore/StrongBox, iOS
  Keychain). It is **not** a KDF wrap: no `kdf_id`/`kdf_params` apply, and the platform owns the
  blob's internal structure (Android `iv ‖ ciphertext`; iOS Keychain payload). It is stored in the
  optional `hardware_wrap` field of the KeyStore blob (§3.1), **never** as a `WrapEntry`, is
  **device-local** (never written to a backup manifest), and unlocks with **no user secret** — the
  gate is the platform's. It currently gates on **device-unlocked**; per-use biometric prompting is
  added with the UI layer. Because `hardware_wrap` is optional and absent by default, a vault with no
  hardware slot serializes exactly as before — existing passphrase/recovery blobs are byte-for-byte
  unchanged and still open.
- Cross-device restore requires: backup ciphertext + manifest + (passphrase or recovery key). Nothing else.

### 3.1 KeyStore blob (`keys/mk.wrapped`)

The MK wraps are the **bootstrap root**: you need them *to obtain* MK, so — unlike the backup
manifest (§4) — the KeyStore blob is **never** a `.dchm`-under-MK. It is a standalone
CBOR-serialized structure, held in memory as an opaque `ByteArray` and persisted verbatim to
`keys/mk.wrapped` (the file write is the storage layer's concern, not the codec's).

```
KeyStore {
  format_version : u,                 # 1; readers reject a higher version (§6)
  wraps          : [ WrapEntry ],     # ≥ 1 KDF wrap; one per passphrase/recovery unlock path
  hardware_wrap  : HardwareWrapEntry? # OPTIONAL; absent by default (omitted, zero bytes)
}
WrapEntry {                           # KDF wraps only — HARDWARE is carried separately
  slot        : enum { PASSPHRASE, RECOVERY },
  kdf_id      : enum { PBKDF2_SHA256, HKDF_SHA256, ARGON2ID },   # see registry below
  kdf_params  : { salt, … },          # PBKDF2: salt, iterations · HKDF: salt, info · Argon2id: salt, memoryKib, iterations, parallelism
  nonce       : bytes(12),            # fresh random per wrap; the AES-256-GCM nonce
  wrapped_mk  : bytes                 # GCM ciphertext ‖ 16-byte tag over MK (32 bytes plaintext)
}
HardwareWrapEntry {                   # slot is implicitly HARDWARE; there is at most one
  blob        : bytes                 # OPAQUE platform output; MUST be non-empty. No kdf_id/kdf_params.
}
```

- **`kdf_id` registry.** `ARGON2ID` — the passphrase-slot default (version 0x13; params `salt`,
  `memoryKib`, `iterations`, `parallelism`). `PBKDF2_SHA256` — the supported passphrase fallback
  (`salt`, `iterations`). `HKDF_SHA256` — the recovery slot (`salt`, `info`). A reader that encounters
  a `kdf_id` it does not support reports `UnsupportedFormatVersion`. Argon2id params, like PBKDF2's,
  must be sane (`memoryKib`, `iterations`, `parallelism` all > 0, and `salt` at least **8 bytes** —
  the Argon2 floor / RFC 9106; DarkChamber's own wraps use 16) or the blob is a `MalformedContainer`.
- **Hardware wrap (`hardware_wrap`).** Optional and **absent by default**. When present, its `blob`
  MUST be non-empty, else the blob is a **`MalformedContainer`**; **no** `kdf_id`/`kdf_params` checks
  apply to it (it is opaque). It is produced/consumed only by the platform `SecureKeyStore`
  (`wrap`/`unwrap`), carries no user secret, and is **device-local** — it is never included in a
  backup manifest (§4). Adding the field does **not** change the bytes of a KeyStore blob that has no
  hardware slot: the CBOR encoder omits an absent optional, so existing passphrase/recovery vaults
  re-encode identically and decode with `hardware_wrap` absent (old vaults always open).
- **Wrap AAD binds the parameters.** Each wrap is sealed with
  `aad = canonical(slot ‖ kdf_id ‖ kdf_params)` (a deterministic byte encoding). An attacker who
  edits `kdf_id` or `kdf_params` (e.g. to force a weaker KDF or fewer iterations) breaks the GCM tag
  → `IntegrityFailure`. Nonces are per-wrap random; every wrap uses a distinct wrap-key, so there is
  no cross-wrap nonce-reuse concern.
- **Reject taxonomy** (reuses §6, no new error classes): a wrong secret, or any tamper of
  `wrapped_mk` / `nonce` / `kdf_params`, fails GCM → **`IntegrityFailure`** (possible tampering, never
  "update the app"); an unknown `kdf_id` or a `format_version` above supported → **`UnsupportedFormatVersion`**;
  structurally malformed CBOR → **`MalformedContainer`**.
- **Re-wrapping.** Changing the passphrase, or adding/rotating the recovery slot, re-seals MK into a
  new `WrapEntry` and rewrites the blob — MK itself is unchanged, so **no object data is
  re-encrypted**. Destroying all wraps (panic wipe) crypto-shreds the vault.
- The layout is designed so a future **alternate MK** (decoy vault, Phase 2) is expressible without a
  format bump; that feature is out of scope for v1 and not built here.

## 4. Backup manifest (Phase 3 — reserved)

The cloud backup manifest (mapping object ids → remote objects → chunk states, plus MK wraps and format versions) will be specified here before Phase 3 ships. It is itself stored as a `.dchm` container encrypted under MK.

## 5. Cipher registry

| cipher_id | Algorithm         | Status                                  |
|-----------|-------------------|-----------------------------------------|
| `0x01`    | AES-256-GCM       | v1, default                             |
| `0x02`    | ChaCha20-Poly1305 | reserved (devices without AES hardware) |

## 6. Versioning & compatibility policy

- Readers MUST reject unknown `magic` (a hard reject — the bytes are not a `.dchm` container).
- Readers MUST reject a `version` above what they support, **and** an unknown/unsupported `cipher_id`,
  with a clear "update the app" error (`UnsupportedFormatVersion`), never a silent failure. This is a
  **distinct** failure from an authentication failure: a GCM tag mismatch is a possible-tampering
  `IntegrityFailure` and MUST NOT be reported as "update the app", and vice versa.
- Writers always write the current version; the app never rewrites old objects in place solely to upgrade them (migration, if ever needed, is explicit and tested).
- **Invariant: every released app version can read every container version ever shipped.** CI enforces this via the committed test-vector suite.

## 7. Test vectors

`docs/format-test-vectors/` (committed with the v1 implementation, under a per-cipher subfolder such
as `aes-256-gcm/`) contains, for each cipher_id, JSON vectors with hex-encoded fixed inputs — `dek`,
`nonce_prefix`, `created_at`, `file_id`, `original_name`, `mime`, `chunk_size`, `plaintext` — and the
expected container bytes (which include the **16-byte prelude** and the header sealed with
nonce `nonce_prefix ‖ 0xFFFFFFFF`, per §2). Positive cases: single-chunk, multi-chunk, and the
**empty-file** case (one empty final chunk, §2.3). Negative vectors that MUST fail: bad tag, reordered
chunks, truncated stream, wrong `is_last_flag`, altered prelude — reported as `IntegrityFailure`; and
unknown `cipher_id` / `version` above supported — reported as `UnsupportedFormatVersion` (§6).

The committed container bytes are produced by the reference codec, so their independence rests on two
things: (1) the **cryptographic primitive** is anchored to published known-answer tests — an all-zero
AES-256-GCM tag and SHA-256("abc") — verified separately from the container codec; and (2) the
**framing** is fully byte-specified in §2 (prelude layout, header/chunk nonces, AAD, little-endian
integers), so an independent implementation can reproduce the same container bytes from the same
inputs. A future independent-tool-computed container vector may be added to strengthen (1) at the
container level. The CI format gate runs all vectors on every commit.
