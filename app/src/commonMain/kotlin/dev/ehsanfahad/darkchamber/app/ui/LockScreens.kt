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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.animation.core.tween
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.StackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
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
import dev.ehsanfahad.darkchamber.core.designsystem.prefersReducedMotion
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.presentation.VaultAlbumsContent
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
            // **The gate is asymmetric on purpose, and the `when` is what makes the lock half
            // true.** Unlocking fades the vault in; locking replaces this branch in the same frame,
            // exactly as it always has. See [UnlockedFadeIn] — a reader coming here to "fix" the
            // missing lock animation should read that first.
            when (val c = child) {
                is RootChild.Locked -> LockedContent(c.component)
                is RootChild.Unlocked -> UnlockedFadeIn { UnlockedContent(c.component) }
            }
        }
    }
}

/**
 * Fades the unlocked vault in — **and has no counterpart for locking, which is the point.**
 *
 * A fade rather than a slide: the unlocked subtree is being *built*, not pushed in from the side, and
 * a page turn is the wrong metaphor for a shutter opening (`docs/DESIGN.md` §8).
 *
 * **Locking is not animated, for a reason stronger than taste.** A fade *out* would keep the unlocked
 * subtree composed for the length of it — so decrypted previews would still be on screen after the
 * keys are gone. The teardown zeroizes the master key and purges the scratch directory *before* the
 * gate publishes `Locked`, by design, so a fading lock screen would be rendering what the session no
 * longer has: not a leak, but a few hundred milliseconds of plaintext bought for decoration. The
 * `when` above therefore drops the whole subtree in one frame, which is also what "locking must feel
 * instant" asks for.
 *
 * Strictly cosmetic in the other direction too: this wraps an already-constructed subtree in an
 * alpha, so it cannot delay, gate or reorder anything the session does.
 *
 * Reduced motion goes **instant** here rather than to a gentler animation. Elsewhere a cross-fade is
 * what motion degrades *to* (§8); a fade is already that floor, so the only thing left to honour the
 * setting with is to skip it.
 */
@Composable
private fun UnlockedFadeIn(content: @Composable () -> Unit) {
    if (prefersReducedMotion()) {
        content()
        return
    }

    // False for exactly one composition, so the animation has somewhere to start. An
    // `animateFloatAsState` that begins at its target renders fully opaque and never animates — the
    // same trap the aperture has, where a freshly composed one snaps instead of opening.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = DarkChamberTheme.motion.motionBase),
        label = "unlock-fade",
    )

    Box(modifier = Modifier.graphicsLayer { this.alpha = alpha }) { content() }
}

/**
 * The gate is **one** screen with several steps, not several screens. The scaffold — and with it the
 * aperture — is called once, here, so the iris keeps a single identity as the step changes and can
 * animate between open fractions (docs/DESIGN.md §7.3: "while keys unwrap, the iris opens from
 * closed"). Calling the scaffold inside each step's branch instead gives every step its own iris,
 * and a freshly composed [Aperture] starts *at* its target — it would snap, never animate.
 */
@Composable
private fun LockedContent(component: LockedComponent) {
    val state by component.state.subscribeAsState()
    val step = state.step
    GateScaffold(
        aperture = step.aperture(state.busy),
        title = step.title(),
        error = state.error,
        busy = state.busy,
    ) {
        when (step) {
            is LockStep.Create -> CreateVaultStep(state, component::submitCreate)
            is LockStep.Recovery -> RecoveryKeyStep(
                recoveryKey = step.recoveryKey,
                copied = state.recoveryKeyCopied,
                onCopy = component::copyRecoveryKey,
                onConfirm = component::confirmRecoverySaved,
            )
            is LockStep.EnableBiometric -> EnableBiometricStep(state, component::enableBiometric, component::skipBiometric)
            is LockStep.Unlock -> UnlockStep(state, component::submitPassphraseUnlock, component::startBiometricUnlock)
        }
    }
}

/** Where the iris sits for each step. Half-open is "in the middle of getting you in". */
private fun LockStep.aperture(busy: Boolean): ApertureState = when (this) {
    // Key derivation (Argon2id) takes real seconds — the iris opening is the progress.
    is LockStep.Create -> if (busy) ApertureState.Unlocking else ApertureState.Closed
    is LockStep.Recovery -> ApertureState.Unlocking
    is LockStep.EnableBiometric -> ApertureState.Unlocking
    is LockStep.Unlock -> if (busy) ApertureState.Unlocking else ApertureState.Closed
}

private fun LockStep.title(): String = when (this) {
    is LockStep.Create -> "Create your vault"
    is LockStep.Recovery -> "Save your recovery key"
    is LockStep.EnableBiometric -> "Unlock with biometrics?"
    is LockStep.Unlock -> "Unlock"
}

@Composable
private fun CreateVaultStep(state: LockState, onCreate: (ByteArray) -> Unit) {
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

@Composable
private fun RecoveryKeyStep(
    recoveryKey: String,
    copied: Boolean,
    onCopy: () -> Unit,
    onConfirm: () -> Unit,
) {
    Text(
        // "Write it down" leads, and stays: copying is an addition, not a replacement. A clipboard
        // is not somewhere safe, and this key is the only way back into the vault.
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

    // Secondary, and kept visually apart from "I've saved it": copying the key is not the same act
    // as having saved it, and the two must not be confusable.
    TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) { Text("Copy") }

    if (copied) {
        Text(
            // Exactly what happens, and nothing more (hard rule #6). Not "copied securely" — the
            // clipboard is readable by other apps, and on Android the clear needs this app to still
            // be running. "About a minute" is a description, not a promise.
            "Copied. It clears from the clipboard in about a minute — paste it somewhere safe now.",
            style = DarkChamberTheme.typography.caption,
            color = DarkChamberTheme.colors.textSecondary,
        )
    }

    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("I've saved it") }
}

@Composable
private fun EnableBiometricStep(state: LockState, onEnable: () -> Unit, onSkip: () -> Unit) {
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

@Composable
private fun UnlockStep(
    state: LockState,
    onPassphrase: (ByteArray) -> Unit,
    onBiometric: () -> Unit,
) {
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

@OptIn(ExperimentalDecomposeApi::class)
@Composable
private fun UnlockedContent(component: UnlockedComponent) {
    // The window insets are applied once, here, for the whole unlocked subtree — the bar and every
    // feature screen below it. Applying them again inside a screen would inset twice (a gap under
    // the bar), and leaving them to each screen means the first one that forgets sits under the
    // system bars.
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val stack by component.stack.subscribeAsState()
        val child = stack.active.instance

        // Subscribed rather than read once: an album can be renamed from the screen the bar is
        // sitting above, and a crumb that did not follow it would be the only stale name on screen.
        val gallery = when (child) {
            is UnlockedChild.Gallery -> child.component.state.subscribeAsState().value
            is UnlockedChild.Albums -> null
        }

        UnlockedBar(
            onLock = component::lock,
            // Offered from the vault's own grid only. From inside an album, or from the album grid
            // itself, the way onward is back — a second entry point to the screen you are standing
            // next to is noise.
            onAlbums = component::openAlbums.takeIf {
                child is UnlockedChild.Gallery && child.scope == GalleryScope.WholeVault
            },
            crumb = crumbFor(child, gallery),
        )

        // The offer to leave, which only the vault's own grid ever makes. Drawn over the screen
        // rather than above it in the column: it is transient, and a row appearing and disappearing
        // in the chrome would move the grid under the user's finger.
        val offeringToLeave by component.leaveNotice.subscribeAsState()

        Box(modifier = Modifier.weight(1f)) {
            // `Children`, not a `when` over the active child: read directly, a screen is simply
            // replaced in one frame, which on a stack of siblings leaves nothing to say which way
            // the user went.
            Children(
                stack = stack,
                animation = predictiveBackAnimation(
                    // The host's handler, which the gate wired up — the same one the gallery's
                    // selection callback uses. This is what lets the user's own gesture drag the
                    // outgoing screen on Android 13 and later rather than only animating after the
                    // press.
                    backHandler = component.backHandler,
                    // **The only pop.** The stack is built with `handleBackButton = false` for this
                    // reason: two registered handlers would pop twice on one gesture.
                    onBack = component::onBack,
                    fallbackAnimation = navigationAnimation(),
                ),
            ) { created ->
                when (val instance = created.instance) {
                    is UnlockedChild.Gallery ->
                        // The session owns the loader (it decrypts through the session's vault and
                        // holds previews in memory), so the screen is handed one rather than making
                        // its own. Absent only in a graph with no platform context, which production
                        // never is — both platform modules bind one and a test says so.
                        component.imageLoader?.let { loader ->
                            VaultGalleryContent(instance.component, loader)
                        }
                    // The same loader, for the same reason: an album's cover is one of its items, so
                    // it is drawn through the session's encrypted-preview path rather than a second
                    // one.
                    is UnlockedChild.Albums ->
                        component.imageLoader?.let { loader ->
                            VaultAlbumsContent(instance.component, loader)
                        }
                }
            }

            // Only while the offer stands. No animation and no timer of its own: the component owns
            // how long the offer lasts, and a notice that faded out on its own schedule would be on
            // screen when the next press no longer leaves — or gone while it still does.
            if (offeringToLeave) LeaveNotice(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * How one unlocked screen gives way to another: a slide, or a cross-fade when the system asks for
 * reduced motion.
 *
 * **Reduced motion drops to a fade rather than to nothing** — `docs/DESIGN.md` §8 asks for motion to
 * be replaced by cross-fades, not removed, because an instant swap is what leaves a user unsure
 * whether anything happened. The same `prefersReducedMotion()` the grid's tile arrival already uses,
 * so the two cannot disagree.
 *
 * The duration is the navigation motion token. A number here would be a fourth opinion about how
 * fast this app moves.
 */
@Composable
private fun <C : Any, T : Any> navigationAnimation(): StackAnimation<C, T> {
    val spec = tween<Float>(durationMillis = DarkChamberTheme.motion.motionBase)

    return if (prefersReducedMotion()) {
        stackAnimation(fade(animationSpec = spec))
    } else {
        stackAnimation(slide(animationSpec = spec))
    }
}

/**
 * The one chrome the unlocked subtree owns: an open aperture, where you are, the way into albums,
 * and the manual lock.
 *
 * [onAlbums] is null on every screen but the vault's own grid, and the entry point is simply absent
 * then rather than present and disabled — a dead control invites a tap and explains nothing.
 *
 * [crumb] is the listing this bar is above, in address-bar form — see `crumbFor`, which decides it.
 * Null on the vault itself, where a second label would only repeat the name beside it.
 */
@Composable
private fun UnlockedBar(onLock: () -> Unit, onAlbums: (() -> Unit)?, crumb: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DarkChamberTheme.spacing.spaceMd, vertical = DarkChamberTheme.spacing.spaceXs),
    ) {
        // Weighted, so the actions on the right are measured first and keep their space. This row is
        // the tightest on the surface — an album's name can be any length the user typed — and the
        // failure to avoid is a long name pushing Lock off the screen.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Aperture(
                state = ApertureState.Open,
                contentDescription = "Vault unlocked",
                size = DarkChamberTheme.spacing.spaceLg,
            )
            Text(
                "Vault",
                style = DarkChamberTheme.typography.title,
                color = DarkChamberTheme.colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.padding(start = DarkChamberTheme.spacing.spaceXs),
            )
            crumb?.let { here ->
                Text(
                    CRUMB_SEPARATOR,
                    style = DarkChamberTheme.typography.title,
                    // Quieter than either side of it: a separator is punctuation, not a third thing
                    // to read.
                    color = DarkChamberTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = DarkChamberTheme.spacing.spaceXxs),
                )
                Text(
                    here,
                    style = DarkChamberTheme.typography.title,
                    color = DarkChamberTheme.colors.textPrimary,
                    maxLines = 1,
                    // Ellipsised, and the only part of the row allowed to shrink: the vault's own
                    // name is two syllables and fixed, while this is whatever was typed.
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            onAlbums?.let { open ->
                TextButton(onClick = open) {
                    Text(
                        "Albums",
                        style = DarkChamberTheme.typography.label,
                        color = DarkChamberTheme.colors.accent,
                    )
                }
            }
            TextButton(onClick = onLock) {
                Text("Lock", style = DarkChamberTheme.typography.label, color = DarkChamberTheme.colors.accent)
            }
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
    // Only true while work is actually running. The half-open aperture is a *visual* state some
    // steps sit in permanently (saving the recovery key, choosing biometrics) — tying the spinner
    // to it showed a progress indicator for work that had already finished.
    busy: Boolean = false,
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
        if (busy) {
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

/**
 * Between the vault's name and the listing below it. A path separator, because that is what the row
 * is: where you are, read left to right.
 */
private const val CRUMB_SEPARATOR = "/"
