package com.alaminahamed.batteryhealth.domain

/**
 * Where a value came from. Surfaced in the UI so a manufacturer-reported number and
 * a number this app measured are never presented as the same kind of claim.
 */
enum class Source { Framework, Measured, Privileged }

/**
 * Every metric in this app is a Reading. Absence is a value, not an exception and
 * not a zero, so no screen can accidentally render a missing metric as data.
 */
sealed interface Reading<out T> {

    data class Available<out T>(val value: T, val source: Source) : Reading<T>

    /** This device does not provide the metric at all. */
    data object Unsupported : Reading<Nothing>

    /** The privileged tier would provide it, but no transport is connected. */
    data object NeedsPrivilegedAccess : Reading<Nothing>

    /** Derived from measurement that has not gathered enough sessions yet. */
    data object NotYetMeasured : Reading<Nothing>
}

val Reading<*>.isAvailable: Boolean
    get() = this is Reading.Available

fun <T> Reading<T>.valueOrNull(): T? = (this as? Reading.Available<T>)?.value

inline fun <T, R> Reading<T>.map(transform: (T) -> R): Reading<R> = when (this) {
    is Reading.Available -> Reading.Available(transform(value), source)
    Reading.Unsupported -> Reading.Unsupported
    Reading.NeedsPrivilegedAccess -> Reading.NeedsPrivilegedAccess
    Reading.NotYetMeasured -> Reading.NotYetMeasured
}
