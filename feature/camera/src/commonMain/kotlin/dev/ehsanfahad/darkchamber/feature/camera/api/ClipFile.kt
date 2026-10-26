package dev.ehsanfahad.darkchamber.feature.camera.api

import kotlinx.io.RawSource
import kotlinx.io.files.Path

/**
 * The file one recording is written to: a scratch file in the vault's private `tmp/`, which the app
 * provides over `core:storage` so this module touches no filesystem API of its own.
 *
 * It holds **plaintext video** from the moment the recorder writes to it. Whoever holds it deletes it
 * on every way out — saved, failed or abandoned; the purge of `tmp/` on lock and on launch is the
 * backstop, not the plan. Its path and name are never logged (hard rule #4).
 */
interface ClipFile {
    /** Where the recorder writes. Nothing exists there until it does. */
    val path: Path

    /** The format the recorder writes there, which the saved item's MIME and name follow. */
    val container: VideoContainer

    /** A fresh reader over the whole file, independent of any other; throws if it cannot be read. */
    fun open(): RawSource

    /** The file's size in bytes, or `null` when there is no file to measure. */
    fun size(): Long?

    /** Remove the file. Idempotent; never throws. */
    fun delete()
}
