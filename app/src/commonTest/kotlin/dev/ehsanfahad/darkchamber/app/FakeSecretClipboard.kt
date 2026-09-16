package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.app.system.SecretClipboard

/**
 * Records what the gate asked to copy, so the recovery step's behaviour can be tested without a
 * device. The platform flags the real implementations set — local-only, sensitive, expiring — are
 * the part a host test cannot see, which is why they carry device-checklist rows instead.
 */
class FakeSecretClipboard : SecretClipboard {
    val copied = mutableListOf<String>()

    val lastCopied: String? get() = copied.lastOrNull()

    override fun copySecret(text: String) {
        copied += text
    }
}
