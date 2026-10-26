package dev.ehsanfahad.darkchamber.core.storage

import kotlinx.io.files.Path

/**
 * Creates [dir], which `createDirectories` left missing, or throws the platform's own failure for
 * it. On the JVM `createDirectories` ignores a `mkdirs()` that returned false, so a directory the
 * disk had no room for went unreported until an open beneath it failed with ENOENT — and a full
 * disk was never named. Throwing here, with the platform's errno in the cause, lets [writeFailure]
 * tell a full disk from any other failure.
 */
internal expect fun createMissingDirectory(dir: Path)
