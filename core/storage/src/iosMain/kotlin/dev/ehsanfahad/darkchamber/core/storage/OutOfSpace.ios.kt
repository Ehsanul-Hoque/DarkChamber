package dev.ehsanfahad.darkchamber.core.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.ENOSPC
import platform.posix.strerror

// kotlinx-io reports a failed native write only as a message built from `strerror(errno)`, so the
// platform's own text for ENOSPC is what is looked for — taken from the platform, never typed here.
@OptIn(ExperimentalForeignApi::class)
private val noSpaceText: String? = strerror(ENOSPC)?.toKString()

internal actual fun isOutOfSpace(failure: Throwable): Boolean {
    val text = noSpaceText ?: return false
    return failure.causeChain().any { it.message?.contains(text) == true }
}
