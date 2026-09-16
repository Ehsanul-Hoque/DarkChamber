# Code Patterns

> Part of the [Engineering Guide](./README.md). DI, navigation & the session gate, MVI, logging, error handling.

## 1. Dependency injection — Koin

- Each feature owns a Koin module in its `di/` folder, binding `api` interfaces to `impl` classes.
- `core` modules export their own Koin modules (`cryptoModule`, `storageModule`, `dbModule`).
- The **app module is the composition root** — the only place that depends on every `:impl` and calls `startKoin { modules(...) }` via an `expect/actual` initializer in `core:model`-adjacent plumbing.
- No `core:di` aggregator — it would invert the isolation rule.
- **Session-scoped features expose a factory instead of a module.** A feature whose dependencies live
  in the vault's session scope (anything reaching `VaultObjects`) cannot own a Koin module without
  either hardcoding the app's scope qualifier or resolving from the root graph, where a live session
  must never be registered. Those features export one factory function taking what they need, and the
  app calls it from inside the session scope — `:feature:vault`'s `vaultGalleryComponent(...)` is the
  first. Features with only root-scope dependencies still own a module.

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

## 3. Presentation — the component *is* the state holder

There is no separate ViewModel layer. A Decompose component holds its own state and exposes named
intents; the composable renders it and calls them. One lifecycle system, no `androidx.lifecycle`
dependency in feature modules, and the same shape from the session gate down through every feature:

```kotlin
interface VaultGalleryComponent {
    val state: Value<GalleryState>   // immutable UI state
    fun importPhotos()               // named intents, not onIntent(sealed Intent)
    fun dismissError()
}

class DefaultVaultGalleryComponent(
    componentContext: ComponentContext,
    private val imports: VaultImports,
    mainContext: CoroutineContext = Dispatchers.Main,
) : VaultGalleryComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(mainContext + SupervisorJob())
    private val _state = MutableValue(GalleryState())
    override val state: Value<GalleryState> = _state

    init { doOnDestroy { scope.cancel() } }
}
```

Rules that carry weight:

- **`mainContext` is injected**, defaulting to `Dispatchers.Main`, so host tests drive components
  without a platform main looper. Long or blocking work moves to its own injected context.
- **Guards are set synchronously**, before the coroutine is scheduled — a busy/importing flag set
  inside `launch` lets a double tap through.
- **State holds ids and display metadata, never decrypted bytes.** Decrypted content lives in
  session-scoped caches, so a retained `State` cannot pin plaintext past a lock.
- **One-shot effects are added per feature only where a screen needs one.** Most do not; an empty
  `Flow<Effect>` on every component is ceremony.

Tests assert on `Value` directly. A component whose work is a `Flow` uses Turbine on that flow
([`TESTING.md`](./TESTING.md)); note that under `UnconfinedTestDispatcher` a coroutine started by an
intent completes inline, so a concurrency guard needs `StandardTestDispatcher` to be exercised at all.
The same inlining hides intermediate states: a `StateFlow` driven to completion within one call
conflates everything but the last value, so a test that needs to observe work *while* it runs has to
queue it.

### 3.1 Work that outlives a screen but not the session

Some work is longer-lived than the screen that starts it. Importing fifty files takes minutes; the
user may well navigate away, and losing the batch because they did would be absurd. Equally, it must
not survive a lock.

The pattern: the work lives in an object owned by the **session scope**, running on the session's own
job, and the component only watches it.

```kotlin
// In the unlocked subtree, which owns the session scope:
private val featureWork = SupervisorJob()          // cancelled FIRST on lock
private val imports = vaultImports(…, scope = CoroutineScope(mainContext + featureWork))

// The screen takes it as a dependency and forwards intents; it does not own it.
class DefaultVaultGalleryComponent(private val imports: VaultImports, …) {
    init { imports.state.onEach(::onImportState).launchIn(scope) }
    override fun importFiles() = imports.importFiles()
}
```

What makes it safe is the **ordering on lock**: cancel the session's work, *then* zeroize the key,
purge scratch, close the driver, dispose the scope. Work that has not yet entered a blocking call
stops before the session it runs against dies; work already inside one is stopped by the guarded
source it reads from, within a buffered read. Lock never *waits* — it runs on the UI thread, and
delaying the zeroize to wait for an encrypt would be the wrong trade in both directions.

Rules that come with it:

- **The state is a `StateFlow` the screen observes**, so a component destroyed and rebuilt mid-work
  picks it straight back up. Anything the screen needs to draw goes in that state whole; flattening
  it into component flags means two copies of the same truth, one always a frame behind.
- **Guards belong to the work, not the screen** — it is the thing that knows whether a batch is
  already running, and it stays right across a rebuild.
- **Nothing is persisted across a lock.** So if a lock can end the work, the user has to be told
  *before* it starts: afterwards, the state is gone with the session and there is nobody left to
  tell.
- **A screen being destroyed must not cancel it.** Worth a test of its own — otherwise the next
  person to tidy up a `doOnDestroy` reintroduces the bug the pattern exists to prevent.

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
        data object UnsupportedFormatVersion : Crypto      // newer .dchm version/cipher than this app
        data object MalformedContainer : Crypto            // bytes aren't a valid .dchm (bad magic / truncated prelude)
        data class KeystoreUnavailable(val cause: Throwable) : Crypto
    }
    data object StorageFull : VaultError
    data class Io(val cause: Throwable) : VaultError
}

// Resolved: a hand-rolled sealed Outcome<T> in core:model — Ok(value) | Err(VaultError),
// no Arrow. The full VaultError above is the target; core:model grows cases as needed.
sealed interface Outcome<out T> { /* Ok(value) | Err(error) — see core:model */ }
```

`data` maps platform/library exceptions to `VaultError`; `presentation` pattern-matches into user-facing states. Two rules with security weight:

- **`IntegrityFailure` is never silently retried or partially rendered.** A failed GCM tag means tampering or corruption; the item is flagged, the user is told plainly, and the event is loggable (id only).
- **`UnsupportedFormatVersion` produces the "update the app" path** required by [`FORMAT.md`](../FORMAT.md) §6 — never a generic error, never a crash.
