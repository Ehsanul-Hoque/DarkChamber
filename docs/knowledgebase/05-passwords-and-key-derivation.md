# 5 · Passwords & key derivation

*Part of the [DarkChamber Knowledgebase](./README.md). Educational, not normative.*

Page 1 said keys must be unpredictable, and pages 2–3 used a fully random 32-byte key. But humans
type *passphrases*, not 32 random bytes. This page bridges that gap — and explains why the tools
here are deliberately **slow**.

## Why a passphrase isn't a key

A random 256-bit key has 2²⁵⁶ possibilities — unguessable. A human passphrase has far less real
randomness ("entropy"): people reuse words, patterns, substitutions. You can't just *use the
passphrase bytes as the key*; it would be far too easy to guess. You need to convert it into a key in
a way that's expensive for an attacker to attack — a **key-derivation function (KDF)**.

## Why plain hashing (page 4) is the wrong tool

You might think "hash the password → use the digest as the key." Two problems:

1. **Hashes are fast — and that helps the attacker.** A cracker with a GPU tries *billions* of
   password guesses per second against a fast hash like SHA-256. Speed is a feature for integrity
   checks (page 4) and a liability for passwords.
2. **Identical passwords hash identically.** If two users pick `hunter2`, the digests match — an
   attacker can precompute a table of common-password hashes once and look everyone up ("rainbow
   tables"), and can see who shares a password.

Password hashing fixes both with two ideas: **salts** and **deliberate slowness**.

## Salt (and a word on pepper)

A **salt** is a unique, random value generated per password and stored *next to* the result (it isn't
secret). It's mixed in before hashing, so the same password produces a *different* output every time.

- Defeats precomputation — a rainbow table would have to be rebuilt for every salt, which is
  infeasible.
- Hides the fact that two users chose the same password.

A **pepper** is a related idea: a secret value kept *separately* from the database (e.g. in code or an
HSM) and mixed in too, so a stolen database alone isn't enough. Optional; complements salt.

## Make it slow on purpose: work factors

A password KDF is tuned to take a noticeable amount of work — say **~100–250 ms** per attempt on real
hardware. To you, unlocking once, that's imperceptible. To an attacker trying billions of guesses,
multiplying every attempt by that cost makes large-scale cracking wildly expensive. The knobs that
set the cost are **work factors** (iteration count, memory used, parallelism). As hardware gets
faster, you raise them.

## The password/KDF functions you'll meet

| Function     | Idea                                      | Notes                                                                                              |
|--------------|-------------------------------------------|----------------------------------------------------------------------------------------------------|
| **PBKDF2**   | Hash the password many thousands of times | Old, widely supported; only *CPU*-hard, so custom hardware attacks it well. A reasonable fallback. |
| **bcrypt**   | Purpose-built slow password hash          | Long-serving; limited memory hardness.                                                             |
| **scrypt**   | Deliberately **memory**-hard              | Forces attackers to spend RAM, not just CPU — blunts GPU/ASIC farms.                               |
| **Argon2id** | Modern, memory-hard, tunable              | Winner of the Password Hashing Competition; today's default recommendation.                        |

**Memory-hardness** is the modern key: cheap specialized cracking hardware has lots of compute but
limited fast memory, so a function that demands memory levels the field.

## How DarkChamber uses this

DarkChamber derives a key from your passphrase with **Argon2id**, tuned to memory-hard work factors,
with a per-vault **salt** stored alongside. That derived key doesn't encrypt your files directly —
it *wraps* (encrypts) the vault's real master key. Changing your passphrase re-derives and re-wraps;
it never has to re-encrypt your data. The exact parameters and where they live are specified in
[`FORMAT.md`](../FORMAT.md) §3, and the full key hierarchy is on the next page.

> [!NOTE]
> **Key takeaways**
> - A human passphrase isn't random enough to be a key — a **KDF** converts it into one.
> - **Plain fast hashes are wrong for passwords**: attackers try billions/second. Password KDFs are
>   **deliberately slow**.
> - A **salt** (unique, per-password, stored in the clear) defeats precomputation and hides shared
>   passwords; a **pepper** adds a separately-kept secret.
> - Prefer a **memory-hard** function — **Argon2id** (or scrypt). PBKDF2 is an acceptable fallback.
>   DarkChamber uses Argon2id.

Next → [How DarkChamber uses all this](./06-how-darkchamber-uses-crypto.md)
