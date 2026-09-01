package dev.ehsanfahad.darkchamber.core.vault

// iOS has no JVM system properties; the sim run uses the default volume. The real 2 GiB device
// proof is the on-device checklist (docs/verification/streaming-2gb-manual.md), not this test.
actual fun streamTestBytes(): Long = DEFAULT_STREAM_BYTES
