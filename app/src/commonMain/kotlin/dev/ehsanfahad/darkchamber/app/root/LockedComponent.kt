package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** The step the locked surface is showing (its little create → recovery → enable → unlock flow). */
sealed interface LockStep {
    /** First run: set a passphrase. */
    data object Create : LockStep

    /** Show the recovery key **once** and require an explicit "I saved it" (docs/DESIGN.md §10). */
    data class Recovery(val recoveryKey: String) : LockStep

    /** Optional: enroll biometric unlock. */
    data object EnableBiometric : LockStep

    /** Returning user: unlock (biometric primary if enrolled, passphrase fallback). */
    data object Unlock : LockStep
}

/** A recoverable, non-fatal lock-screen error, surfaced as honest copy by the UI (hard rule #6). */
enum class LockError {
    WrongPassphrase,
    BiometricCanceled,
    BiometricUnavailable,
    HardwareInvalidated,
    CreateFailed,
    EnrollFailed,
    ProbeFailed,
}

/**
 * The locked surface's UI state. Holds **no decrypted bytes** — the live session lives only in the
 * component's private `pendingVault` until it is handed to the gate (the session-gate rule).
 */
data class LockState(
    val step: LockStep,
    val busy: Boolean = false,
    val hasBiometric: Boolean = false,
    /** §3.3: after a biometric invalidation/unavailability, force the passphrase path. */
    val forcePassphrase: Boolean = false,
    val error: LockError? = null,
)

/**
 * The locked child: the create-or-unlock surface. Drives [VaultBootstrap] and, on success, hands the
 * live vault up to the gate via the root's `onUnlocked` hook. Intents launch on a lifecycle-scoped
 * coroutine scope; the UI (LockScreens) renders [state] and calls the intents.
 */
interface LockedComponent {
    val state: Value<LockState>

    fun submitCreate(passphrase: ByteArray)
    fun submitPassphraseUnlock(passphrase: ByteArray)
    fun startBiometricUnlock()
    fun confirmRecoverySaved()
    fun enableBiometric()
    fun skipBiometric()
    fun dismissError()
}

class DefaultLockedComponent(
    componentContext: ComponentContext,
    private val bootstrap: VaultBootstrap,
    private val authenticator: BiometricAuthenticator,
    mainContext: CoroutineContext = Dispatchers.Main,
    private val onUnlocked: (VaultBootstrap.OpenVault) -> Unit,
) : LockedComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(mainContext + SupervisorJob())
    private var pendingVault: VaultBootstrap.OpenVault? = null

    private val _state = MutableValue(probeState())
    override val state: Value<LockState> = _state

    init {
        doOnDestroy { scope.cancel() }
    }

    // A quick keystore probe (no catalog) picks create-vs-unlock and whether biometric is offered.
    private fun probeState(): LockState =
        when (val p = bootstrap.probe()) {
            is Outcome.Ok ->
                if (!p.value.exists) {
                    LockState(LockStep.Create)
                } else {
                    LockState(LockStep.Unlock, hasBiometric = p.value.hasHardwareSlot)
                }
            // Can't tell if a vault exists — offer unlock (createVault would refuse to clobber anyway).
            is Outcome.Err -> LockState(LockStep.Unlock, error = LockError.ProbeFailed)
        }

    override fun submitCreate(passphrase: ByteArray) = launchGuarded {
        when (val r = bootstrap.createVault(passphrase)) {
            is Outcome.Ok -> {
                pendingVault = r.value.vault
                _state.value = LockState(LockStep.Recovery(r.value.recoveryKey))
            }
            is Outcome.Err -> _state.value = _state.value.copy(busy = false, error = LockError.CreateFailed)
        }
    }

    override fun confirmRecoverySaved() {
        if (_state.value.step is LockStep.Recovery) _state.value = LockState(LockStep.EnableBiometric)
    }

    override fun enableBiometric() {
        val vault = pendingVault ?: return
        launchGuarded {
            // Enrollment prompts once; if the user cancels or it fails, proceed anyway — the passphrase
            // slot always unlocks, so biometric stays optional (never a data-loss trap).
            bootstrap.enrollBiometric(vault, authenticator)
            finishUnlock(vault)
        }
    }

    override fun skipBiometric() {
        pendingVault?.let { finishUnlock(it) }
    }

    override fun submitPassphraseUnlock(passphrase: ByteArray) = launchGuarded {
        when (val r = bootstrap.unlock(passphrase)) {
            is Outcome.Ok -> finishUnlock(r.value)
            is Outcome.Err -> _state.value = _state.value.copy(busy = false, error = LockError.WrongPassphrase)
        }
    }

    override fun startBiometricUnlock() = launchGuarded {
        when (val r = bootstrap.unlockWithBiometric(authenticator)) {
            is Outcome.Ok -> finishUnlock(r.value)
            is Outcome.Err -> _state.value = mapUnlockError(r.error)
        }
    }

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // Serialize intents behind the busy flag; clear the error before a fresh attempt.
    private inline fun launchGuarded(crossinline block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        scope.launch { block() }
    }

    private fun mapUnlockError(error: VaultError): LockState {
        val cleared = _state.value.copy(busy = false)
        return when (error) {
            VaultError.Auth.Cancelled -> cleared.copy(error = LockError.BiometricCanceled)
            VaultError.Auth.Unavailable -> cleared.copy(error = LockError.BiometricUnavailable, forcePassphrase = true)
            // §3.3: re-enrolled biometrics killed the hardware key — force passphrase, drop the biometric CTA.
            VaultError.Crypto.IntegrityFailure ->
                cleared.copy(error = LockError.HardwareInvalidated, forcePassphrase = true, hasBiometric = false)
            else -> cleared.copy(error = LockError.BiometricCanceled)
        }
    }

    private fun finishUnlock(vault: VaultBootstrap.OpenVault) {
        pendingVault = null
        onUnlocked(vault)
    }
}
