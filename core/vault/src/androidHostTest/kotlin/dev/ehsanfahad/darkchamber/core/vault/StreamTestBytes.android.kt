package dev.ehsanfahad.darkchamber.core.vault

// Host reads the size override from the JVM system property (wired as a test systemProperty in the
// build), e.g. `-Ddchm.stream.bytes=2147483648` for the 2 GiB opt-in run; default otherwise.
actual fun streamTestBytes(): Long =
    System.getProperty("dchm.stream.bytes")?.toLongOrNull() ?: DEFAULT_STREAM_BYTES
