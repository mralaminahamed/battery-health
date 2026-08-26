package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source

/**
 * Cycle count, resolved from whatever sources this app can genuinely obtain with no
 * privileged access of any kind: the battery broadcast's `EXTRA_CYCLE_COUNT` (framework)
 * and this app's own count, derived from charge it watched go in ([MeasuredCycles]).
 *
 * There used to be a third, higher-ranked source here: Samsung's own accumulated
 * `mSavedBatteryUsage` figure, read only through a privileged shell (adb or root). That
 * shell tier is gone -- this app now asks for nothing it cannot get through a normal
 * Android permission flow -- so the field this function resolves is a plain two-source
 * fallback rather than a three-way precedence.
 *
 * There was also a user-supplied baseline, entered by hand from a figure the user read on
 * their own phone. That is gone too, for the same reason the design-capacity override is
 * gone: the app no longer asks anyone to type a number in, only to grant permissions the
 * normal way.
 *
 * Pure and JVM-tested directly: `BatteryRepository` itself cannot be constructed in a JVM
 * test (its other dependencies are real Android types), so the one piece of logic this
 * feature needed unit-tested on its own merits lives here instead.
 */
internal object CycleCountResolver {

    /**
     * - [broadcastCycles] present -> `Available(Framework)`. The platform's own figure,
     *   preferred over this app's derived one wherever it exists.
     * - Otherwise [measured] passes through as-is: `Available` becomes this app's own
     *   `Source.Measured` count, `NotYetMeasured` says a figure is still accumulating
     *   (recording is on but has not seen enough charge yet), and anything else (no design
     *   capacity known, so [MeasuredCycles.fromSessions] itself returned `Unsupported`)
     *   resolves to `Unsupported` -- there is no further tier left that might still supply
     *   it, so there is nothing left to invite the user to unlock.
     */
    fun resolve(
        broadcastCycles: Int?,
        measured: Reading<Int> = Reading.Unsupported,
    ): Reading<Int> = when {
        broadcastCycles != null -> Reading.Available(broadcastCycles, Source.Framework)
        measured is Reading.Available -> Reading.Available(measured.value, Source.Measured)
        measured is Reading.NotYetMeasured -> measured
        else -> Reading.Unsupported
    }
}
