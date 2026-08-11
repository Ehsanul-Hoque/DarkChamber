package dev.ehsanfahad.darkchamber.core.model

/**
 * Typed result crossing layer boundaries — no exceptions leak across modules
 * (see docs/engineering/PATTERNS.md §5). Either a success [Ok] carrying a value or
 * a failure [Err] carrying a [VaultError]. Hand-rolled (no Arrow dependency).
 */
sealed interface Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>
    data class Err(val error: VaultError) : Outcome<Nothing>
}

/** Collapse an [Outcome] to a single value by handling both branches. */
inline fun <T, R> Outcome<T>.fold(onOk: (T) -> R, onErr: (VaultError) -> R): R =
    when (this) {
        is Outcome.Ok -> onOk(value)
        is Outcome.Err -> onErr(error)
    }

/** Transform a success value, leaving a failure untouched. */
inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> =
    when (this) {
        is Outcome.Ok -> Outcome.Ok(transform(value))
        is Outcome.Err -> this
    }

/** The success value, or `null` if this is a failure. */
fun <T> Outcome<T>.getOrNull(): T? =
    when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> null
    }
