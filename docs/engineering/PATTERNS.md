# Code Patterns

> Part of the [Engineering Guide](./README.md). DI, navigation & the session gate, MVI, logging, error handling.

## 1. Dependency injection — Koin

- Each feature owns a Koin module in its `di/` folder, binding `api` interfaces to `impl` classes.
- `core` modules export their own Koin modules (`cryptoModule`, `storageModule`, `dbModule`).
- The **app module is the composition root** — the only place that depends on every `:impl` and calls `startKoin { modules(...) }` via an `expect/actual` initializer in `core:model`-adjacent plumbing.
- No `core:di` aggregator — it would invert the isolation rule.

```kotlin
// feature/vault/impl/di/VaultModule.kt
val vaultModule = module {
    single<VaultRepository> { VaultRepositoryImpl(get(), get(), get()) }
    factory { ImportItemUseCase(get(), get()) }
    viewModel { VaultViewModel(get(), get()) }
}
```

## 2. Navigation & the session gate — Decompose

Decompose owns the component tree, back stack, and config serialization. Each feature's `:api` exposes a component interface + config; the app maps configs to components at the root.

**DarkChamber-specific: the session gate is structural.** The root component has exactly two children:

```
RootComponent
├── LockedComponent      # unlock screens (PIN, biometric, recovery)
└── UnlockedComponent    # entire feature tree — REQUIRES a live VaultSession
```

`UnlockedComponent` is *constructed with* a non-null `VaultSession` (the in-memory MK holder). When the session locks (background grace expiry, device lock, panic), the root **replaces the whole Unlocked subtree with Locked** — every screen, viewer, and decrypted bitmap cache is torn down by construction, not by per-screen cleanup code. No feature screen can exist without a session because its parent cannot exist without one. This is the compile-time enforcement of ARCHITECTURE.md §8.

## 3. Presentation — MVI ViewModels

KMP `androidx.lifecycle.ViewModel` retained via Decompose. Hand-rolled MVI contract (no extra dependency):

```kotlin
interface MviViewModel<S, I, E> {
    val state: StateFlow<S>     // immutable UI state
    val effects: Flow<E>        // one-shot effects (navigation, snackbars)
    fun onIntent(intent: I)
}
```

Pairs with Turbine tests asserting exact `State`/`Effect` sequences ([`TESTING.md`](./TESTING.md)).

State classes in viewer/vault features hold **references (item ids), not decrypted bytes** — decrypted content lives in session-scoped caches inside `core:storage`/Coil, so a retained `State` never pins plaintext past a lock.

## 4. Logging — local, honest, minimal

**Kermit** facade in `core:logging`. Exactly two writers, both local:

1. **Console/platform writer** — debug builds only (Logcat / Xcode console). Stripped from release.
2. **Diagnostic file writer — opt-in, user-readable.** Off by default. When a user enables "diagnostic log" in settings (e.g., to report a bug), plain-text logs are written to app-private storage, **viewable and exportable by the user in-app**, with a visible banner while active and an expiry (auto-off after 24h). The user attaches it to a GitHub issue *themselves* after reading it.

Explicitly rejected (and why, so it isn't re-proposed):

- **No crash/analytics SDKs (Crashlytics etc.).** Violates the no-trackers commitment and the Play "no data collected" declaration. Crash triage relies on user-attached diagnostics and store-console basic stats.
- **No developer-only-readable (asymmetrically encrypted) logs.** A file on the user's device that only we can decrypt is the exact inversion of DarkChamber's premise, regardless of technical merit elsewhere.

**Hard rule (Konsist-guarded heuristically, review-enforced absolutely):** logs never contain file names, note titles/content, album names, key material, passphrases, or any plaintext derived from vault items. Log identifiers, event names, sizes, and error codes only. `toString()` on domain models is overridden to redact.

## 5. Error handling — typed results

Use cases and repositories return typed results; no exceptions crossing layer boundaries:

```kotlin
sealed interface VaultError {
    data object AuthRequired : VaultError                  // session locked/expired
    data object AuthFailed : VaultError                    // wrong PIN/biometric
    sealed interface Crypto : VaultError {
        data object IntegrityFailure : Crypto              // GCM tag mismatch — tampering or corruption
        data object UnsupportedFormatVersion : Crypto      // newer .dchm than this app
        data class KeystoreUnavailable(val cause: Throwable) : Crypto
    }
    data object StorageFull : VaultError
    data class Io(val cause: Throwable) : VaultError
}

typealias Outcome<T> = Either<VaultError, T>   // or Result-style; pick once, in core:model
```

`data` maps platform/library exceptions to `VaultError`; `presentation` pattern-matches into user-facing states. Two rules with security weight:

- **`IntegrityFailure` is never silently retried or partially rendered.** A failed GCM tag means tampering or corruption; the item is flagged, the user is told plainly, and the event is loggable (id only).
- **`UnsupportedFormatVersion` produces the "update the app" path** required by [`FORMAT.md`](../FORMAT.md) §6 — never a generic error, never a crash.
