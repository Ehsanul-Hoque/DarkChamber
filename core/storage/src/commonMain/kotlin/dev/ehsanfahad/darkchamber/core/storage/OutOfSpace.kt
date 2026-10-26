package dev.ehsanfahad.darkchamber.core.storage

import dev.ehsanfahad.darkchamber.core.model.VaultError

/**
 * Whether [failure] — or anything in its cause chain — is the platform reporting that the device
 * ran out of space during the write that threw it. Only the platform's own report counts: a
 * free-space reading taken beforehand, or a message that merely mentions space, is never enough,
 * because copy that names this cause must be true of the failure in hand.
 *
 * Public for one caller outside this module: the `.dchm` codec writes through a sink it is
 * handed and reports any failure in its loop as a plain `Io`, so `core:vault` recovers the cause
 * with this rather than every failed encrypt reading as a generic write error.
 */
expect fun isOutOfSpace(failure: Throwable): Boolean

/** The error a failed write maps to: [VaultError.Storage.Full] when the platform said so, else [VaultError.Io]. */
internal fun writeFailure(failure: Exception): VaultError =
    if (isOutOfSpace(failure)) VaultError.Storage.Full else VaultError.Io(failure)

internal fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }
