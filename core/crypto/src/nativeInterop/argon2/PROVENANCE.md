# Vendored: phc-winner-argon2 (reference Argon2 implementation)

This directory holds a **subset** of the phc-winner-argon2 reference sources, vendored so
the iOS `argon2` cinterop can build `libargon2.a` from committed C — never a
prebuilt binary blob (F-Droid reproducible-builds goal). It provides the
Argon2id used for the passphrase key-wrap slot (`docs/FORMAT.md` §3).

## Upstream

- Project: <https://github.com/P-H-C/phc-winner-argon2>
- Release tag: **20190702**
- Commit: **62358ba2123abd17fccf2a108a301d4b52c01a7c**
- License: dual **CC0-1.0 / Apache-2.0** (see `LICENSE`) — GPLv3-compatible.

## What was vendored (and what was not)

Only the portable reference build set is included:

| File                                                                      | Role                                                                                                                        |
|---------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `include/argon2.h`                                                        | public API (we call `argon2id_hash_raw`)                                                                                    |
| `src/argon2.c`                                                            | top-level `argon2*_hash*` entry points                                                                                      |
| `src/core.c` / `src/core.h`                                               | Argon2 core (fill memory, finalize)                                                                                         |
| `src/ref.c`                                                               | **portable** block-fill (`fill_segment`) — used instead of the SIMD `opt.c` so one source set compiles for every Apple arch |
| `src/encoding.c` / `src/encoding.h`                                       | encoded-hash helpers (pulled in by `argon2.c`)                                                                              |
| `src/thread.c` / `src/thread.h`                                           | pthreads wrapper (parallelism lanes)                                                                                        |
| `src/blake2/blake2b.c`, `blake2.h`, `blake2-impl.h`, `blamka-round-ref.h` | BLAKE2b + reference BlaMka round                                                                                            |

**Deliberately excluded:** the SIMD `src/opt.c` (replaced by `ref.c`), the CLI/driver/
benchmark/self-test/KAT-generator files (`run.c`, `bench.c`, `test.c`, `genkat.c`,
`genkat.h`) and their SIMD header `blamka-round-opt.h`. `core.c`'s `#include "genkat.h"`
is guarded by the `GENKAT` macro, which is never defined in our build.

## sha256 (as vendored)

```
df20cb726a2fe6bccc736b81ea0d86219766a0d17f1794c19e048a34830ca1cc  include/argon2.h
72a93deebc5fd76bec0c6d300a2d92b500fb354b26babbddbc6d8b88681e663d  src/argon2.c
8ac91f1f57d94235f8de0069b7809be1deb365c3b37adb8e30423cd364aff09a  src/blake2/blake2-impl.h
3c2de197dd23179f78c57deac7b73a6049778bf651c5db57e508b2cfce5e7559  src/blake2/blake2.h
52519ccbc1e48f489ff444091f630d05dadcc8f1ee61c5b7361e3a9618299c9a  src/blake2/blake2b.c
bcfdcf785218cf897f05b144e80b659e611188fee3887d533bdcb7a6aa4c336b  src/blake2/blamka-round-ref.h
0f53eb2370f8971f04fcf043b8083bf81d1530c48b2ead71c0b6c4e22a01aeec  src/core.c
c9665623cb3d306f63b6a3effd87bcfab971c28253c77e111445e42c1523235e  src/core.h
e20124ec0f780f88527e79cf00825e839624ed1f469c7e231596bf070f8c7b14  src/encoding.c
42f283d12ec445cfb423ac2f1f5a5d1a7f152ce2b9363f6ec3e1d5b61cd4ee6d  src/encoding.h
4ec47b080c22f4ee416b9dbcfae70ee6007fcfba427f870d7b84395846fa1bc7  src/ref.c
1402085e2f54b021ea31b0d301f4b689a8b300832e2d11901502e62835185e3e  src/thread.c
9eab7f9ff356862a00a3075478dd0059344953ed79daab8b29185be2010efe78  src/thread.h
220f8736a89ff51c92ef3d497f413b48e6cf1df3d6278bc909c6308c78e1718e  LICENSE
```

## Build

`build-argon2.sh <sdk> <target-triple> <out-dir>` compiles the set above into
`libargon2.a` with the Xcode toolchain clang for one Apple target. `core/crypto/build.gradle.kts`
invokes it per iOS arch (`iphoneos` / `iphonesimulator`) and feeds the result to the
`argon2` cinterop via `-staticLibrary libargon2.a` + `-libraryPath`. Argon2 version is
pinned to **0x13** (RFC 9106 / Argon2 1.3) in the callers, not here.

## Updating

Re-copy the same file subset from a new upstream tag, refresh the commit/tag/sha256 above,
and re-run `:core:crypto:cinteropArgon2IosSimulatorArm64` + the Argon2id KATs.
