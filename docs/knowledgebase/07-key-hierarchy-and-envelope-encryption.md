# 7 · The key hierarchy & envelope encryption

*Part of the [DarkChamber Knowledgebase](./README.md). Educational, not normative — the binding spec
is [`FORMAT.md`](../FORMAT.md) §3.*

Page 6 sketched the layers of keys. This page zooms in on the **mechanism** — the part now built in
code — and answers the question that trips up most newcomers: *if my files are encrypted with keys,
what encrypts the keys?*

## Wrapping: encrypting a key with another key

"Wrapping" sounds special but it's just **encryption whose plaintext happens to be a key**. To wrap a
32-byte key `K` under a wrapping key `W`, you AEAD-encrypt it exactly like any other bytes (pages 2–3):

```
wrapped = AES-256-GCM(key = W, nonce = random, aad = context, data = K)   ‖ 16-byte tag
```

To *unwrap*, you decrypt with `W`. If `W` is wrong — wrong passphrase, tampered bytes — the GCM tag
fails and you get an integrity error, not a silently-wrong key. That's the whole trick. Everything
below is just *which* keys wrap *which*.

## Two levels: MasterKey and DEKs

- **MasterKey (MK)** — one random 256-bit key for the whole vault.
- **DEK (Data-Encryption Key)** — one random 256-bit key **per object** (each file, note, thumbnail).

MK never touches file content. **MK wraps the DEKs**; each **DEK encrypts one object** as a `.dchm`
container (page 6). Why two levels instead of encrypting everything under MK directly?

- **Delete = destroy one DEK.** Throw away an object's wrapped DEK and its ciphertext is permanent
  noise — instant, irreversible **crypto-shredding**, no overwriting needed.
- **Per-object isolation.** A unique key per object means a mistake with one never endangers another,
  and every object gets its own fresh nonces.

## Wrapping the MasterKey: the KeyStore and its slots

MK itself is never stored in the clear. It's kept **wrapped several independent ways**, so there's
more than one route in. Each route is a **slot**; all of them wrap *the same MK*:

```
KeyStore (the keys/mk.wrapped file)
 ├─ PASSPHRASE slot : wrapKey = KDF(your passphrase, salt)      → wrapped MK
 ├─ RECOVERY   slot : wrapKey = HKDF(your recovery key)         → wrapped MK
 └─ HARDWARE   slot : opaque wrap by a platform-keystore key    → wrapped MK   (device-local)
```

The two **KDF slots** (passphrase, recovery) each store: which KDF they used (`kdf_id`), that KDF's
parameters (salt, iteration count…), the GCM nonce, and the wrapped MK bytes. Unlocking = pick a
slot, derive its wrap key from your secret, unwrap MK into memory. Locking = zeroize MK. **MK lives
only in RAM, never on disk unwrapped.**

The **HARDWARE slot is different**: there's no secret you type and no KDF. The platform's secure
hardware (Android Keystore/StrongBox, iOS Keychain) holds a key that never leaves it and wraps MK
for you — so the slot is stored as one **opaque platform blob**, not a `kdf_id` + parameters entry.
It's **device-local** (never in a backup) and, this cycle, gated on the **device being unlocked**;
a per-use biometric prompt is added later with the UI. It protects *our own MK wrap* — it is not an
app-lock (see page 6 / ADR-002).

Because every slot wraps the same MK independently, you can **change your passphrase** by re-deriving
and re-wrapping *only the passphrase slot* — the MK, the DEKs, and every encrypted file are untouched.

## Why the slot's parameters are authenticated (AAD again)

Notice the wrap's `aad = context` above. DarkChamber sets it to the slot's own metadata —
`slot ‖ kdf_id ‖ kdf_params` — so those fields are **bound into the GCM tag** (the AAD idea from
page 3). An attacker who edits the stored iteration count down to `1`, or swaps the KDF for a weaker
one, to make cracking cheaper, breaks the tag: the unwrap fails as tampering instead of quietly using
the weakened parameters. The cost knobs are stored in the clear (so they can be *raised* over time),
but they can't be secretly *lowered*.

## Passphrase slot vs recovery slot: two different KDFs, on purpose

Page 5 explained that a passphrase must be stretched by a slow, memory-hard KDF because it has little
entropy. The **recovery key is different**: it's 256 random bits — already full entropy. Stretching it
slowly would buy nothing (there's no weak password to protect). So:

| Slot       | Secret                    | KDF                              | Why                                             |
|------------|---------------------------|----------------------------------|-------------------------------------------------|
| PASSPHRASE | your typed passphrase     | **Argon2id** (PBKDF2 fallback)   | low entropy → must be **slow & memory-hard**    |
| RECOVERY   | a random 256-bit key      | **HKDF-SHA256** (fast)           | full entropy → just needs clean *derivation*    |

HKDF isn't a password stretcher; it's a **key-derivation** step that turns one good key into another
(here, a wrap key), with a domain-separation label so the recovery wrap key can't collide with any
other use. Right tool for a full-entropy input.

## The recovery key: your way back on a new device

The passphrase and hardware slots are great day to day, but a **brand-new phone has no hardware slot**
and you might forget a passphrase. The **recovery key** is the escape hatch: a random 256-bit key
DarkChamber generates once and asks you to write down. Because it's random bytes, it's shown as
**grouped [Crockford base32](https://www.crockford.com/base32.html) with a checksum** — an alphabet
that drops the look-alike letters `I L O U`, folds the ones people still confuse (`I/L→1`, `O→0`), and
adds a check character so a mistyped key is caught *before* it wastes an unlock attempt. The **raw
bytes are what matters**; the text is just a human-transcribable skin over them.

## What exists today

As of this cycle the **key layer is complete** — all three slots exist and Argon2id is the default
passphrase KDF: MasterKey/DEK generation, the KeyStore wrap format, **Argon2id** passphrase wraps
(64 MiB / 3 / 2, with PBKDF2 still supported for older wraps), HKDF recovery wraps, the recovery-key
encoding, and an in-memory session that holds MK and hands out DEKs (zeroizing them after use) — all
with committed [KeyStore test vectors](../format-test-vectors/keystore) for both KDFs. The
**hardware slot is now built too**, behind the `SecureKeyStore` seam: an Android Keystore/StrongBox
key and an iOS Keychain item wrap MK, **gated on the device being unlocked**. The integration is
host-tested with a fake; the real platform code is verified by an
[on-device checklist](../verification/secure-keystore-manual.md). Still to come with the first UI
cycle: the **per-use biometric prompt** (this cycle only creates the device-unlocked key).

> [!NOTE]
> **Key takeaways**
> - **Wrapping** a key is just AEAD-encrypting it under another key; unwrap fails loudly if the
>   wrapping key or bytes are wrong.
> - One **MasterKey** wraps many **per-object DEKs**; destroying a DEK crypto-shreds one object.
> - MK is stored only **wrapped**, in several **slots** (passphrase, recovery, hardware) — any one
>   unlocks the same MK; changing your passphrase re-wraps just that slot.
> - The slot's **KDF parameters are AAD-bound**, so they can be raised but never secretly weakened.
> - The **passphrase** slot uses a slow memory-hard KDF; the full-entropy **recovery key** uses fast
>   HKDF and is shown as checksummed Crockford base32.

← Back to the [Knowledgebase index](./README.md) · Normative spec: [`FORMAT.md`](../FORMAT.md) §3
