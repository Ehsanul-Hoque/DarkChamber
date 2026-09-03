# Manual verification — 2 GB streaming encrypt / stream-decrypt (bounded RAM)

> Scope: the streaming vault-object pipeline — the **Phase 0 exit criterion**: *encrypt → decrypt a 2 GB video by streaming,
> on both platforms, with plaintext never written to disk.* Verifies that `:core:vault`'s
> `importObject`/`openObject` move an object far larger than RAM through **O(chunkSize) memory** —
> the part the small fast tests deliberately do not size up to. Run at release, and whenever
> `core:vault`, `DchmCodec`, or the storage/session streaming paths change.

## What is already automated (run these first)

- **Fast correctness (host + iOS sim):** `./gradlew :core:vault:testAndroidHostTest
  :core:vault:iosSimulatorArm64Test` — import → row → stream-decrypt round-trip across boundaries
  (empty / partial-last-chunk / multi-chunk), `openMeta` without the object, tamper →
  `IntegrityFailure`, `shredObject`, and the fail-closed row-failure cleanup (`VaultObjectsTest`),
  plus the bounded-RAM streaming round-trip at the **default 16 MiB** (`VaultStreamingTest`).
- These prove correctness at every chunk boundary. This checklist adds the **2 GiB scale + a memory
  observation**, which no CI-default size can show.

## The 2 GiB opt-in run (same code path, bigger volume)

`VaultStreamingTest` pushes `streamTestBytes()` bytes through import → open with a re-openable
generating source and a discarding hashing sink, asserting the byte count + SHA-256 match — so the
plaintext is **never materialised whole**. Raise the size to 2 GiB and watch memory:

- **Android / JVM host:**
  `./gradlew :core:vault:testAndroidHostTest -Pdchm.stream.bytes=2147483648 --rerun-tasks`
  (`--rerun-tasks` defeats the up-to-date cache so it actually re-runs.) This host test runs on your
  machine's JVM, **not** as an app on a device — so Android Studio's *device* Profiler does not apply
  (there is no device process to attach to). To watch memory, either read the GC logs below, or
  attach a JVM profiler (**VisualVM** or `jconsole`, both in the JDK) to the forked Gradle **test
  worker** JVM. For GC logs, put the flag on the test JVM via the environment — it is **not** a Gradle CLI
  flag: `JAVA_TOOL_OPTIONS="-verbose:gc" ./gradlew :core:vault:testAndroidHostTest
  -Pdchm.stream.bytes=2147483648 --rerun-tasks`. Needs ~2 GiB of scratch disk under the system temp
  dir for the persisted container.
- **iOS simulator (arm64):**
  `DCHM_STREAM_BYTES=2147483648 ./gradlew :core:vault:iosSimulatorArm64Test`
  For a memory graph, run the test target from Xcode with the `DCHM_STREAM_BYTES` env var set in the
  scheme and attach **Instruments → Allocations**.

### Pass criteria

- [ ] The test passes at 2 GiB on the JVM host.
- [ ] The test passes at 2 GiB on the iOS simulator.
- [ ] **Memory stays flat** for the whole run — resident/heap tracks O(chunkSize) (≈ a few MiB of
      live buffers), **not** the object size. No monotonic climb toward 2 GiB, no OOM.
- [ ] Wall time scales roughly linearly with size (AES-GCM throughput-bound), confirming a single
      streaming pass, not a whole-file buffer.

## Plaintext-never-on-disk check

The exit criterion forbids plaintext touching shared storage. During/after a run, confirm:

- [ ] The only large file written is the **encrypted** `.dchm` container under
      `<vaultRoot>/objects/ab/cd/<file_id>.dchm` — its bytes are ciphertext (a `DCHM` magic prelude,
      then GCM chunks), never the plaintext.
- [ ] Nothing is written under `tmp/` that outlives the run, and nothing lands outside the app-
      private vault root (no MediaStore / shared cache / Photos entry).
- [ ] `openObject` streams to the caller's sink only — the decrypt path writes **no** plaintext file
      (the sink here discards; a real viewer decodes in memory — Phase 1).

## End-to-end on a physical device (Phase 1 re-run)

Phase 0 ships **no capture/import UI**, so the full *real 2 GB video through the running app on a
physical Pixel/iPhone, killed and relaunched, then decrypted* is exercised when the camera/import +
viewer features land (Phase 1). At that point, re-run this checklist end-to-end:

- [ ] Import a real ~2 GB video via the app, force-quit, relaunch, biometric-unlock, and play/decrypt
      it — memory flat, playback starts without a full decrypt, and no plaintext copy appears in
      shared storage or Files/Photos.

  This is where Android Studio's **device** Profiler applies (the app is a real on-device process):
  pick **Track Memory Consumption (Java/Kotlin Allocations)** and start it *with* the app launch
  ("Process start"). Watch the live graph, not a one-shot Heap Dump. iOS: Instruments → Allocations.

Until then, the streaming **capability** (bounded-RAM encrypt + 2 GiB stream-decrypt) is proven by
the opt-in run above on both platforms; kill → relaunch → biometric-unlock is already covered by
`docs/verification/session-gate-manual.md`.
