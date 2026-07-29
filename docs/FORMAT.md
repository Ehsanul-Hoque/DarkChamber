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
[ MAGIC "DCHM" (4 bytes) | u8 version=1 | u8 cipher_id | u16 header_len ]
[ encrypted header ]
[ chunk 0 ][ chunk 1 ] … [ chunk N ]
```

All multi-byte integers are little-endian.

### 2.1 Prelude (plaintext, 8 bytes)

| Field      | Size | Value                                     |
|------------|------|-------------------------------------------|
| magic      | 4    | ASCII `DCHM`                              |
| version    | 1    | `0x01`                                    |
| cipher_id  | 1    | see §5                                    |
| header_len | 2    | byte length of the encrypted header block |

### 2.2 Encrypted header

Serialized (kotlinx.serialization, CBOR) then encrypted with the object's DEK; the 8-byte plaintext prelude is bound as AAD so it cannot be altered without detection.

Fields: `original_name`, `mime`, `plaintext_size`, `created_at`, `sha256(plaintext)`, `chunk_size`, `nonce_prefix` (8 random bytes).

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

- **Nonce uniqueness:** fresh random `nonce_prefix` per object + counter per chunk → unique nonces within an object; a fresh random DEK per object → no cross-object reuse. Nonce reuse under GCM is catastrophic; both mechanisms are mandatory.
- **AAD binding** of `file_id`, `chunk_index`, and `is_last_flag` makes chunk reordering, truncation, and cross-file splicing fail authentication.
- Decryption MUST verify every chunk's tag before releasing its plaintext, MUST verify `is_last_flag` on the final chunk, and SHOULD verify the header's `sha256(plaintext)` on full-file reads.

## 3. Key hierarchy (what a decryptor needs)

This section specifies enough of the key model to decrypt a vault or backup given user secrets; implementation details of hardware storage live in `ARCHITECTURE.md` §3.

```
MasterKey (MK): 256-bit random, generated at vault creation.

Wraps of MK stored on disk / in backup manifest:
  wrap_pw       = AES-256-GCM(key = Argon2id(passphrase, salt, params), data = MK)
  wrap_recovery = AES-256-GCM(key = KDF(recovery_key), data = MK)
  wrap_hw       = platform-keystore wrap (device-local only; never in backups)

Per-object: dek_wrapped = AES-256-GCM(key = MK, data = DEK), stored in the catalog
            (and in the backup manifest for cloud restore).
```

- **Argon2id parameters** (`m`, `t`, `p`, salt) are stored alongside `wrap_pw` so they can be raised over time; initial targets: m = 64 MiB, t = 3, p = 2 (tuned to ~100–250 ms on mid-range devices).
- **Recovery key:** 256-bit random, presented to the user as BIP39-style words or grouped base32. `wrap_recovery` MUST be included in backup manifests — it is the only unlock path on a fresh device.
- Cross-device restore requires: backup ciphertext + manifest + (passphrase or recovery key). Nothing else.

## 4. Backup manifest (Phase 3 — reserved)

The cloud backup manifest (mapping object ids → remote objects → chunk states, plus MK wraps and format versions) will be specified here before Phase 3 ships. It is itself stored as a `.dchm` container encrypted under MK.

## 5. Cipher registry

| cipher_id | Algorithm         | Status                                  |
|-----------|-------------------|-----------------------------------------|
| `0x01`    | AES-256-GCM       | v1, default                             |
| `0x02`    | ChaCha20-Poly1305 | reserved (devices without AES hardware) |

## 6. Versioning & compatibility policy

- Readers MUST reject unknown `magic`; readers MUST reject a `version` above what they support with a clear "update the app" error, never a silent failure.
- Writers always write the current version; the app never rewrites old objects in place solely to upgrade them (migration, if ever needed, is explicit and tested).
- **Invariant: every released app version can read every container version ever shipped.** CI enforces this via the committed test-vector suite.

## 7. Test vectors

`docs/format-test-vectors/` (to be committed with the v1 implementation) will contain, for each cipher_id: fixed keys/nonces/plaintexts with expected ciphertext for single-chunk, multi-chunk, and empty-file cases, plus negative vectors (bad tag, reordered chunks, truncated stream, wrong is_last_flag) that MUST fail. The CI format gate runs all vectors on every commit.
