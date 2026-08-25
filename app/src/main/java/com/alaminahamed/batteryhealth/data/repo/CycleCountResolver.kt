package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source

/**
 * Cycle count is the one field `BatteryRepository` can genuinely obtain from either tier:
 * the broadcast's `EXTRA_CYCLE_COUNT` (framework) and Samsung's own accumulated
 * `mSavedBatteryUsage` figure (privileged, once `DumpsysBatteryParser` parses and
 * validates it) can both be present at once, both be absent at once, or split either way.
 * Every other privileged-only field this app reads (ASOC, BSOH, first-use date) has no
 * framework counterpart to weigh against, so `BatteryRepository`'s general
 * `privilegedReading`/`privilegedAbsence` pair -- Available when the dump has it,
 * otherwise Unsupported if a dump was obtained at all, NeedsPrivilegedAccess if it was not -- is
 * the whole answer there. Reusing that general rule unchanged for cycle count would be
 * this codebase's recurring defect shape (a general rule where a more specific one is
 * required) all over again: it has no way to fall back to a real framework reading just
 * because the privileged tier came back empty.
 *
 * Pure and JVM-tested directly: `BatteryRepository` itself cannot be constructed in a JVM
 * test (its other dependencies are real Android types), so the one piece of logic this
 * feature needed unit-tested on its own merits lives here instead.
 */
internal object CycleCountResolver {

    /**
     * [dumpAvailable] carries the same connected-vs-failed-vs-absent distinction
     * `BatteryRepository.privilegedReading` already relies on: `false` only when the
     * privileged tier was never connected (or the shell call itself failed), never merely
     * because this one field happened to be missing from an otherwise-successful dump.
     *
     * - [privilegedCycles] present -> `Available(Privileged)`, regardless of what the
     *   broadcast says: Samsung's own accumulated figure is the more direct measurement of
     *   this device's actual history, so it wins outright rather than merely on points.
     * - [privilegedCycles] absent but [broadcastCycles] present -> `Available(Framework)`.
     *   The fallback the general privileged-only rule cannot express: a real number
     *   already in hand must not be hidden behind "Needs privileged access" or "Not
     *   available" just because the more detailed source did not also have it -- whether
     *   that is because the privileged tier was never connected, or because it was
     *   connected and the dump simply omitted the field.
     * - Both absent -> [dumpAvailable] decides which absence is true, exactly as
     *   `privilegedAbsence` already does for every other field: `Unsupported` once a real
     *   dump was obtained and still did not carry it (reconnecting the privileged tier
     *   again cannot help), `NeedsPrivilegedAccess` when there was no dump to try at all
     *   (it might still help). This is the one branch where connected-but-absent and
     *   never-connected must land differently, and the only reason [dumpAvailable] is
     *   threaded through at all.
     */
    fun resolve(
        privilegedCycles: Int?,
        dumpAvailable: Boolean,
        broadcastCycles: Int?,
    ): Reading<Int> = when {
        privilegedCycles != null -> Reading.Available(privilegedCycles, Source.Privileged)
        broadcastCycles != null -> Reading.Available(broadcastCycles, Source.Framework)
        dumpAvailable -> Reading.Unsupported
        else -> Reading.NeedsPrivilegedAccess
    }
}
