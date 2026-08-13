# `.dchm` format test vectors

Committed, language-agnostic conformance vectors for the DarkChamber container
([`../FORMAT.md`](../FORMAT.md)). The CI format gate (`core:crypto`, host test) runs them on every
commit; third-party decryptors can verify against the same files. Layout: one subfolder per
`cipher_id` (e.g. [`aes-256-gcm/`](./aes-256-gcm) = `cipher_id 0x01`).

## Files

- `<cipher>/positive.json` — an array of cases. Each has fully-specified inputs and the **expected
  container bytes**. A conforming implementation MUST (a) encode the inputs to exactly `container`,
  and (b) decode `container` back to `plaintext`.
- `<cipher>/negative.json` — an array of tampered/invalid cases derived from a named positive by a
  small `mutation`. Decoding each MUST fail with the stated `expected_error`.

All byte fields are lowercase hex. Integer fields (`created_at`, `chunk_size`) are decimal.

### positive case schema
`name, version, cipher_id, dek, nonce_prefix, created_at, file_id, original_name, mime, chunk_size, plaintext, container`

### negative case schema
`name, from` (a positive `name`), `mutation`, `expected_error` (a `VaultError.Crypto` variant:
`IntegrityFailure` | `UnsupportedFormatVersion` | `MalformedContainer`).

Mutations: `set_byte@<offset>=<value>` (decimal), `flip_last_byte`, `truncate_one`, `append_zero`,
`swap_first_two_chunks`, `flip_first_header_byte`.

## Provenance (how the expected values were derived)

- The **cryptographic core is anchored to a published constant**, independent of our codec: a
  separate test asserts AES-256-GCM with an all-zero key/IV and empty input yields the
  NIST/McGrew tag `530f8afbc74536b9a963b4f1c4cb738b`, and SHA-256("abc") its FIPS-180 KAT.
- The **container bytes** in `positive.json` are produced by the reference `DchmCodec` with the
  framing fixed by FORMAT.md §2 (16-byte little-endian prelude, `nonce_prefix ‖ 0xFFFFFFFF` header
  nonce, `nonce_prefix ‖ u32-LE index` chunk nonces, `file_id ‖ u32-LE index ‖ u8 is_last` AAD).
  Because every cipher operation inside is the KAT-anchored primitive and the framing is
  byte-specified, these vectors lock the format against regressions and let independent
  implementations reproduce it exactly.
- Vectors use a small `chunk_size` (16) so the multi-chunk case stays compact; production uses 1 MiB.
