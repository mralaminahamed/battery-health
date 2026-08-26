package com.alaminahamed.batteryhealth.domain

/**
 * Where a value came from. Surfaced in the UI so a manufacturer-reported number and
 * a number this app measured are never presented as the same kind of claim.
 */
enum class Source {
    /** A standard Android API any app can call. */
    Framework,

    /** Derived by this app from its own recorded samples. */
    Measured,

    /** Obtained through the privileged tier -- an adb or root shell. */
    Privileged,

    /**
     * A vendor's own value, read without any privilege -- currently
     * `Settings.Global.protect_battery`, which Samsung populates and which any app may
     * read.
     *
     * Kept apart from [Framework] because it is not a standard Android API and exists only
     * on the vendors that happen to publish it, and apart from [Privileged] because it
     * needed no shell and no setup. Collapsing it into either would misstate both how
     * portable the reading is and what it cost the user to get.
     */
    Vendor,

    /**
     * Derived by this app from foreground screen time apportioning a [Measured] discharge
     * total -- an estimate, not an observation. See `EstimatedAppDrain`'s own doc
     * (`data.repo`) for exactly what this does and does not account for: drain is assumed
     * proportional to time on screen, which is a useful assumption and a false one.
     *
     * Kept apart from [Measured] even though this app derived both: a [Measured] figure is
     * this app's own direct counter/level arithmetic on data that genuinely is charge, while
     * an [Inferred] figure multiplies that same arithmetic by a proxy (screen time) that has
     * no direct bearing on energy at all. Collapsing the two would let an estimate borrow
     * the confidence a real measurement earned.
     */
    Inferred,
}

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

    /**
     * A user-grantable permission would provide it, but is not held yet.
     *
     * Distinct from [NeedsPrivilegedAccess]: that one names an absence only a privileged
     * adb/root shell could close, which this app no longer has any route to at all. This
     * one names an absence a normal user closes themselves with an ordinary Settings
     * toggle -- currently `PACKAGE_USAGE_STATS`, appop-gated with no runtime dialog of its
     * own. Collapsing the two would tell a user who could fix this themselves, right now,
     * that the app is instead waiting on something they have no way to grant.
     */
    data object NeedsUsageAccess : Reading<Nothing>

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
    Reading.NeedsUsageAccess -> Reading.NeedsUsageAccess
    Reading.NotYetMeasured -> Reading.NotYetMeasured
}
