package dev.ehsanfahad.darkchamber.core.logging

import co.touchlab.kermit.Logger
import co.touchlab.kermit.LoggerConfig
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import dev.ehsanfahad.darkchamber.core.model.VaultError

/**
 * `:core:logging` — the app's only logging surface.
 *
 * Hard rule #4: logs carry identifiers, sizes, and error codes only. No file names, note
 * titles/bodies, album names, key material, passphrases, or any plaintext derived from a
 * vault item ever reaches a log line. Hard rule #3: no Crashlytics, no remote sink, ever —
 * Kermit's platform writer (Logcat / NSLog) is the only destination.
 */

/**
 * Configure the process-wide Kermit logger. Called once from the app launcher's bootstrap.
 *
 * Only a local platform writer is attached. Adding a remote/analytics sink here would
 * violate hard rules #3 and #4 — do not.
 *
 * @param minSeverity floor below which logs are dropped (default [Severity.Info]).
 */
public fun initLogging(minSeverity: Severity = Severity.Info) {
    val config: LoggerConfig = StaticConfig(
        minSeverity = minSeverity,
        logWriterList = listOf(platformLogWriter()),
    )
    Logger.setLogWriters(config.logWriterList)
    Logger.setMinSeverity(config.minSeverity)
    Logger.setTag("DarkChamber")
}

/**
 * Reduce a [VaultError] to a stable, content-free code safe to log.
 *
 * The returned token is a fixed type tag (e.g. `Io`, `Db`, `Crypto.IntegrityFailure`) — it
 * NEVER includes the `cause` message, a path, an id, a size, or any vault content. This is the
 * boundary that makes [VaultError.Io]/[VaultError.Db] (which each carry a raw [Throwable])
 * loggable without leaking. Log this token, never the error itself.
 */
public fun redactCause(error: VaultError): String =
    when (error) {
        VaultError.Crypto.IntegrityFailure -> "Crypto.IntegrityFailure"
        VaultError.Crypto.UnsupportedFormatVersion -> "Crypto.UnsupportedFormatVersion"
        VaultError.Crypto.MalformedContainer -> "Crypto.MalformedContainer"
        VaultError.Auth.Cancelled -> "Auth.Cancelled"
        VaultError.Auth.Unavailable -> "Auth.Unavailable"
        VaultError.Auth.Failed -> "Auth.Failed"
        is VaultError.Io -> "Io"
        is VaultError.Db -> "Db"
        is VaultError.Unexpected -> "Unexpected"
    }

/**
 * Log a [VaultError] at error severity as `<stage> failed: <code>`. [stage] MUST be a fixed,
 * caller-supplied token (never content); the error is passed through [redactCause] so no cause
 * message, path, id, or vault content is emitted (hard rule #4).
 */
public fun logVaultError(stage: String, error: VaultError) {
    Logger.e { "$stage failed: ${redactCause(error)}" }
}
