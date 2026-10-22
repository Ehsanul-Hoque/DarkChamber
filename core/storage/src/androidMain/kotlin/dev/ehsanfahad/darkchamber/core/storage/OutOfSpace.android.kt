package dev.ehsanfahad.darkchamber.core.storage

import android.system.ErrnoException
import android.system.OsConstants

// libcore rethrows a failed write as an IOException caused by the ErrnoException it came from, so
// the errno is read by type and number — never from the message.
internal actual fun isOutOfSpace(failure: Throwable): Boolean =
    failure.causeChain().any { it is ErrnoException && it.errno == OsConstants.ENOSPC }
