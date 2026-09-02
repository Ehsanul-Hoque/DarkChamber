package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

// iOS has no JVM system properties; the size override comes from the DCHM_STREAM_BYTES environment
// variable (set it in the Xcode test scheme, or `DCHM_STREAM_BYTES=2147483648` for the 2 GiB opt-in
// run on the simulator). Absent → the default volume.
@OptIn(ExperimentalForeignApi::class)
actual fun streamTestBytes(): Long =
    getenv("DCHM_STREAM_BYTES")?.toKString()?.toLongOrNull() ?: DEFAULT_STREAM_BYTES
