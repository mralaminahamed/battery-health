package com.alaminahamed.batteryhealth.ui.components

import kotlin.math.abs

/**
 * The decision rule behind `PrimitiveLanguageTest.assertNearestExpected`: true when
 * [measuredDp] sits strictly closer to [thisExpectedDp] than to [otherExpectedDp]. Extracted
 * out of that instrumented test so a JVM test (`NearestExpectedTest`) can exercise the
 * arithmetic directly, with no device or emulator needed -- this predicate is pure arithmetic
 * and, per task-4-report.md, carries most of this round's risk: it replaced a fixed tolerance
 * that could not reliably discriminate a 1dp inter-language gap (`cardOuterVertical`,
 * `sectionHeaderBottom`, `unitOffsetStart`, `unitOffsetBottom`).
 *
 * `internal` rather than `private`: Kotlin's `test` and `androidTest` compilations are
 * friend-compiled against `main` by default in this project (see
 * `DumpsysBatteryParser.isPlausibleCycleRate`/`packedDateToEpochDay` for existing precedent, and
 * `DesignCapacityProvider.resolve`), so this stays out of the public surface while remaining
 * reachable from both, without duplicating the comparison in either test source set.
 *
 * A tie (exact midpoint) resolves to `false`: the strict `<` treats "equidistant from both
 * tokens" as *not* nearest, rather than defaulting an ambiguous measurement to a pass. See
 * `NearestExpectedTest.measuredAtTheMidpointFails` for why that is the safer direction.
 */
internal fun isNearestExpected(measuredDp: Float, thisExpectedDp: Float, otherExpectedDp: Float): Boolean =
    abs(measuredDp - thisExpectedDp) < abs(measuredDp - otherExpectedDp)
