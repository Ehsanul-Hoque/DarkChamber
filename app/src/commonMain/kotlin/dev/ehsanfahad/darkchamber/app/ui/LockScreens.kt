package dev.ehsanfahad.darkchamber.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.ehsanfahad.darkchamber.app.root.LockError
import dev.ehsanfahad.darkchamber.app.root.LockState
import dev.ehsanfahad.darkchamber.app.root.LockStep
import dev.ehsanfahad.darkchamber.app.root.LockedComponent
import dev.ehsanfahad.darkchamber.app.root.RootChild
import dev.ehsanfahad.darkchamber.app.root.RootComponent
import dev.ehsanfahad.darkchamber.app.root.UnlockedChild
import dev.ehsanfahad.darkchamber.app.root.UnlockedComponent
import dev.ehsanfahad.darkchamber.core.designsystem.Aperture
import dev.ehsanfahad.darkchamber.core.designsystem.ApertureState
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.feature.vault.presentation.VaultGalleryContent

/**
 * The shared UI for the session gate (docs/DESIGN.md). Copy is honest (hard rule #6): the recovery
 * screen states loss is unrecoverable; nothing promises more than the threat model allows. The
 * aperture is the one signature element, on the unlock/create affordance.
 *
 * The real vault UI is Phase 1 — [UnlockedContent] is a placeholder with a Lock button.
 */
@Composable
fun RootContent(component: RootComponent, modifier: Modifier = Modifier) {
    DarkChamberTheme {
        Surface(modifier = modifier.fillMaxSize(), color = DarkChamberTheme.colors.background) {
            val child by component.child.subscribeAsState()
            when (val c = child) {
                is RootChild.Locked -> LockedContent(c.component)
                is RootChild.Unlocked -> UnlockedContent(c.component)
            }
        }
    }
}

@Composable
private fun LockedContent(component: LockedComponent) {
    val state by component.state.subscribeAsState()
    when (val step = state.step) {
        is LockStep.Create -> CreateVaultScreen(state, component::submitCreate)
        is LockStep.Recovery -> RecoveryKeyScreen(step.recoveryKey, component::confirmRecoverySaved)
        is LockStep.EnableBiometric -> EnableBiometricScreen(state, component::enableBiometric, component::skipBiometric)
        is LockStep.Unlock -> UnlockScreen(state, component::submitPassphraseUnlock, component::startBiometricUnlock)
    }
}

@Composable
private fun CreateVaultScreen(state: LockState, onCreate: (ByteArray) -> Unit) {
    GateScaffold(aperture = ApertureState.Closed, title = "Create your vault", error = state.error) {
        var passphrase by remember { mutableStateOf("") }
        Text(
            "Choose a passphrase. It is the only way in besides your recovery key — we can't reset it.",
            style = DarkChamberTheme.typography.body,
            color = DarkChamberTheme.colors.textSecondary,
        )
        PassphraseField(passphrase, { passphrase = it }, "Passphrase")
        Button(
            onClick = { onCreate(passphrase.encodeToByteArray()) },
            enabled = !state.busy && passphrase.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create vault") }
    }
}

@Composable
private fun RecoveryKeyScreen(recoveryKey: String, onConfirm: () -> Unit) {
    GateScaffold(aperture = ApertureState.Unlocking, title = "Save your recovery key") {
        Text(
            "This is the only way to reach your vault if you forget your passphrase or replace this " +
                "device. Write it down and store it somewhere safe — if you lose it, your data is gone. " +
                "We never see it and can't recover it.",
            style = DarkChamberTheme.typography.body,
            color = DarkChamberTheme.colors.textSecondary,
        )
        Surface(
            color = DarkChamberTheme.colors.surfaceRaised,
            shape = DarkChamberTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                recoveryKey,
                style = DarkChamberTheme.typography.monoKey,
                color = DarkChamberTheme.colors.textPrimary,
                modifier = Modifier.padding(DarkChamberTheme.spacing.spaceMd),
            )
        }
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("I've saved it") }
    }
}

@Composable
private fun EnableBiometricScreen(state: LockState, onEnable: () -> Unit, onSkip: () -> Unit) {
    GateScaffold(aperture = ApertureState.Unlocking, title = "Unlock with biometrics?", error = state.error) {
        Text(
            "Use Face or fingerprint to unlock next time. Your passphrase still works, and you can turn " +
                "this off later.",
            style = DarkChamberTheme.typography.body,
            color = DarkChamberTheme.colors.textSecondary,
        )
        Button(onClick = onEnable, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Enable biometrics")
        }
        TextButton(onClick = onSkip, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Not now")
        }
    }
}

@Composable
private fun UnlockScreen(
    state: LockState,
    onPassphrase: (ByteArray) -> Unit,
    onBiometric: () -> Unit,
) {
    val aperture = if (state.busy) ApertureState.Unlocking else ApertureState.Closed
    GateScaffold(aperture = aperture, title = "Unlock", error = state.error) {
        var passphrase by remember { mutableStateOf("") }
        // Biometric is primary only when a live hardware slot exists and we're not forced to passphrase.
        if (state.hasBiometric && !state.forcePassphrase) {
            Button(onClick = onBiometric, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock with biometrics")
            }
            Text(
                "or use your passphrase",
                style = DarkChamberTheme.typography.caption,
                color = DarkChamberTheme.colors.textSecondary,
            )
        }
        PassphraseField(passphrase, { passphrase = it }, "Passphrase")
        Button(
            onClick = { onPassphrase(passphrase.encodeToByteArray()) },
            enabled = !state.busy && passphrase.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Unlock") }
    }
}

@Composable
private fun UnlockedContent(component: UnlockedComponent) {
    // The window insets are applied once, here, for the whole unlocked subtree — the bar and every
    // feature screen below it. Applying them again inside a screen would inset twice (a gap under
    // the bar), and leaving them to each screen means the first one that forgets sits under the
    // system bars.
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        UnlockedBar(onLock = component::lock)

        val stack by component.stack.subscribeAsState()
        Box(modifier = Modifier.weight(1f)) {
            when (val child = stack.active.instance) {
                is UnlockedChild.Gallery -> VaultGalleryContent(child.component)
            }
        }
    }
}

/** The one chrome the unlocked subtree owns: an open aperture and the manual lock. */
@Composable
private fun UnlockedBar(onLock: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DarkChamberTheme.spacing.spaceMd, vertical = DarkChamberTheme.spacing.spaceXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Aperture(
                state = ApertureState.Open,
                contentDescription = "Vault unlocked",
                size = DarkChamberTheme.spacing.spaceLg,
            )
            Text(
                "Vault",
                style = DarkChamberTheme.typography.title,
                color = DarkChamberTheme.colors.textPrimary,
                modifier = Modifier.padding(start = DarkChamberTheme.spacing.spaceXs),
            )
        }
        TextButton(onClick = onLock) {
            Text("Lock", style = DarkChamberTheme.typography.label, color = DarkChamberTheme.colors.accent)
        }
    }
}

@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Shared gate layout: the aperture, a title, an optional error line, and the screen's content. */
@Composable
private fun GateScaffold(
    aperture: ApertureState,
    title: String,
    error: LockError? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Keep content clear of the status bar / notch (iOS) and system bars (Android edge-to-edge),
            // while the Surface background still fills behind them.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(DarkChamberTheme.spacing.spaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DarkChamberTheme.spacing.spaceMd),
    ) {
        Aperture(state = aperture, contentDescription = title)
        Text(title, style = DarkChamberTheme.typography.titleLarge, color = DarkChamberTheme.colors.textPrimary)
        if (error != null) {
            Text(error.message(), style = DarkChamberTheme.typography.body, color = DarkChamberTheme.colors.danger)
        }
        content()
        if (aperture == ApertureState.Unlocking) {
            Spacer(Modifier.height(DarkChamberTheme.spacing.spaceXs))
            CircularProgressIndicator(color = DarkChamberTheme.colors.accent)
        }
    }
}

/** Honest, plain error copy (docs/DESIGN.md §11; hard rule #6). */
private fun LockError.message(): String = when (this) {
    LockError.WrongPassphrase -> "That passphrase didn't work. Try again."
    LockError.BiometricCanceled -> "Biometric unlock was canceled. Use your passphrase or try again."
    LockError.BiometricUnavailable -> "Biometrics aren't available. Use your passphrase."
    LockError.HardwareInvalidated ->
        "Your biometrics changed, so biometric unlock is disabled. Unlock with your passphrase, then re-enable it."
    LockError.CreateFailed -> "Couldn't create the vault. Please try again."
    LockError.ProbeFailed -> "Couldn't open the vault storage. Unlock with your passphrase."
}
