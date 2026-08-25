package com.alaminahamed.batteryhealth.data.privileged

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Every field independently optional, and `null` on any of them means exactly one thing:
 * this dump did not contain a line this parser recognised for that field. Never a
 * default, never a zero standing in for absence -- `BatteryRepository` is the only place
 * that decides what a `null` here should mean for a `Reading` (`Unsupported` once a real
 * dump was in hand, `NeedsPrivilegedAccess` when there was no dump to try at all), and it needs the
 * true absence to make that call correctly.
 */
data class ParsedBatteryDump(
    /** Samsung's "Aged State Of Charge", `mSavedBatteryAsoc` -- the real state-of-health
     * percentage the whole privileged tier exists to reach. `86` on the fixture this was
     * built against. */
    val asocPct: Int?,
    /** `mSavedBatteryBsoh` -- a second, independent Samsung health figure (`95` on the
     * same fixture, deliberately different from [asocPct]'s `86`). Not the same
     * measurement under a different name; both are reported rather than one discarded. */
    val bsohPct: Int?,
    /** `battery FirstUseDate`, already converted from its packed `yyyyMMdd` int to an
     * epoch day -- see [packedDateToEpochDay]. */
    val firstUseDateEpochDay: Long?,
    /** `mProtectBatteryMode`. Modelled as a plain on/off: `0` is off, any other value
     * (`1` on the fixture) is on. Samsung's mode field may carry more than two states
     * (the same fixture separately logs `[Not Battery Adaptive Protect Mode]`, implying at
     * least one more), but nothing in this app's UI needs the distinction yet -- see the
     * task report for this simplification called out explicitly rather than shipped
     * silently. */
    val protectBatteryModeEnabled: Boolean?,
    /** `mProtectionThreshold` -- the charge percentage Battery Protect caps charging at
     * when enabled. */
    val protectionThresholdPct: Int?,
    /**
     * Samsung's accumulated cycle count, derived from `mSavedBatteryUsage` (hundredths of
     * a cycle -- `61919` on the fixture this was built against, i.e. `619.19`, rounded to
     * `619`). Rounded rather than kept as a decimal: `BatterySnapshot.cycleCount` is a
     * plain `Int`, the same type the framework's own `EXTRA_CYCLE_COUNT` broadcast already
     * reports through, so carrying `.19` of a cycle would need a second, parallel numeric
     * type this app has no other use for, to preserve precision nothing downstream acts
     * on differently. Unlike a naive counter that only advances on a full 0->100
     * discharge, this accumulates every partial charge -- the same definition Apple's own
     * cycle count uses -- which is why it is not a small integer on a lightly-used phone
     * the way a naive counter would be.
     *
     * `null` both when the line is missing (like every other field here) and when
     * [isPlausibleCycleRate] rejects the ÷100 interpretation for this device -- see that
     * function's own doc for why a failed cross-check is treated exactly like an absent
     * line rather than a different, third kind of absence: the honest answer in both cases
     * is "this app does not have a trustworthy number," not a number that might be off by
     * roughly a factor of 100.
     */
    val cycleCount: Int?,
)

/**
 * Pure function over `dumpsys battery`'s text output. No Android types, so this is
 * JVM-tested directly against a real capture (`app/src/test/resources/dumpsys-battery-sm-a356e.txt`)
 * rather than a hand-written fixture that could drift from what the device actually prints.
 *
 * Each field is extracted by its own independent regex against the whole dump, not by
 * splitting into lines and matching a fixed line shape: a One UI update that reorders
 * lines, or a Samsung service that omits one field in some battery states (the fixture's
 * own `[Not Battery Adaptive Protect Mode]` line shows the dump's shape already varies
 * with device state), degrades exactly the one field whose line moved or disappeared,
 * never the others and never a crash.
 */
object DumpsysBatteryParser {

    /**
     * [todayEpochDay] defaults to the real wall-clock date so every production call site
     * (there is exactly one, in `BatteryRepository`) needs nothing extra, while every test
     * below passes an explicit value so [isPlausibleCycleRate]'s day-rate cross-check is
     * deterministic rather than depending on when the test happens to run. It is otherwise
     * unused by every field but [ParsedBatteryDump.cycleCount] -- see that field's own doc.
     */
    fun parse(dump: String, todayEpochDay: Long = LocalDate.now().toEpochDay()): ParsedBatteryDump {
        val firstUseDateEpochDay = intField(dump, FIRST_USE_DATE_REGEX)?.let(::packedDateToEpochDay)
        return ParsedBatteryDump(
            asocPct = intField(dump, ASOC_REGEX),
            bsohPct = intField(dump, BSOH_REGEX),
            firstUseDateEpochDay = firstUseDateEpochDay,
            protectBatteryModeEnabled = intField(dump, PROTECT_MODE_REGEX)?.let { it != 0 },
            protectionThresholdPct = intField(dump, PROTECTION_THRESHOLD_REGEX),
            cycleCount = parseCycleCount(dump, firstUseDateEpochDay, todayEpochDay),
        )
    }

    /**
     * `mSavedBatteryUsage` is documented through Samsung's own `*#9900#` SysDump menu and
     * corroborated by several independent write-ups, but it is community-documented, not
     * officially specified -- so [isPlausibleCycleRate] cross-checks the ÷100
     * interpretation against `battery FirstUseDate` from this same dump before this field
     * ever reports a number, rather than trusting the division unconditionally the way
     * every other field here trusts its own regex.
     */
    private fun parseCycleCount(dump: String, firstUseDateEpochDay: Long?, todayEpochDay: Long): Int? {
        val hundredths = intField(dump, CYCLE_USAGE_REGEX) ?: return null
        // A negative reading is not a smaller cycle count, the same way a negative age is
        // not a younger person -- reject before the rate cross-check below even runs, and
        // regardless of whether a first-use date exists to check it against.
        if (hundredths < 0) return null
        val cycles = (hundredths / 100.0).roundToInt()
        return if (isPlausibleCycleRate(cycles, firstUseDateEpochDay, todayEpochDay)) cycles else null
    }

    /**
     * Cross-checks the ÷100 interpretation of `mSavedBatteryUsage` against how long this
     * battery has been in service, using `battery FirstUseDate` from the very same dump.
     * A real device sits somewhere between a lightly-used tablet charged perhaps once
     * every ten days (~0.1 cycles/day) and a heavily-cycled phone topped up several times
     * a day (~3 cycles/day); [MIN_PLAUSIBLE_CYCLES_PER_DAY] and [MAX_PLAUSIBLE_CYCLES_PER_DAY]
     * are chosen to comfortably cover that whole range with margin on both sides. A ratio
     * outside it means the ÷100 interpretation is not holding *for this device* -- most
     * plausibly the raw units mean something else on that model -- not that this
     * particular battery is unusually old or new: `61919 / 100 = 619` cycles over the
     * fixture's own 775-day-old battery is `0.80` cycles/day, comfortably inside the band,
     * which is the sanity check this guard exists to formalise.
     *
     * This validates the *interpretation*, not the battery -- it must never reject a
     * cycle count on a device where the rate is genuinely plausible, only the specific
     * combination that cannot be telling the truth about hundredths-of-a-cycle.
     *
     * A missing [firstUseDateEpochDay] (the line did not parse -- see this file's own
     * top-level doc: one field's trouble must never become another field's trouble) or one
     * that has not arrived yet ([todayEpochDay] at or before it -- a corrupted or
     * genuinely future-dated field) means the cross-check simply cannot run, so this
     * returns `true` rather than `false`: no evidence the interpretation is wrong is not
     * the same as evidence that it is, and rejecting cycle count over a problem with a
     * different field would be exactly that mistake on a new pair of fields.
     */
    internal fun isPlausibleCycleRate(
        cycles: Int,
        firstUseDateEpochDay: Long?,
        todayEpochDay: Long,
    ): Boolean {
        if (firstUseDateEpochDay == null) return true
        val daysSinceFirstUse = todayEpochDay - firstUseDateEpochDay
        if (daysSinceFirstUse <= 0) return true
        val cyclesPerDay = cycles / daysSinceFirstUse.toDouble()
        return cyclesPerDay in MIN_PLAUSIBLE_CYCLES_PER_DAY..MAX_PLAUSIBLE_CYCLES_PER_DAY
    }

    /**
     * `raw` is a packed `yyyyMMdd` integer (`20240630` for 2024-06-30), **not** an epoch
     * day -- `BatterySnapshot.firstUsageDateEpochDay` and `Formatters.epochDay`
     * (`LocalDate.ofEpochDay`) both expect the latter, so this conversion is mandatory,
     * not cosmetic: passing `20240630` straight through as an epoch day would render a
     * date tens of thousands of years in the future.
     *
     * `null` on any value `LocalDate.of` rejects (an out-of-range month or day, e.g. a
     * corrupted or genuinely malformed field) rather than letting `DateTimeException`
     * escape a pure parsing function.
     */
    internal fun packedDateToEpochDay(raw: Int): Long? = runCatching {
        val year = raw / 10_000
        val month = (raw / 100) % 100
        val day = raw % 100
        LocalDate.of(year, month, day).toEpochDay()
    }.getOrNull()

    private fun intField(dump: String, regex: Regex): Int? =
        regex.find(dump)?.groupValues?.get(1)?.toIntOrNull()

    // `\b` guards both ends of every key literal. Without the leading one,
    // "mProtectionThreshold:" would also match inside "mMaximumProtectionThreshold:" --
    // both keys are genuinely present in the same real dump this was built against, one
    // line apart -- and `Regex.find` would silently prefer whichever happened to appear
    // first rather than reliably picking the field this parser actually means.
    private val ASOC_REGEX = Regex("""\bmSavedBatteryAsoc\b\s*:\s*\[\s*(-?\d+)\s*\]""")
    private val BSOH_REGEX = Regex("""\bmSavedBatteryBsoh\b\s*:\s*(-?\d+)""")
    private val FIRST_USE_DATE_REGEX = Regex("""\bbattery FirstUseDate\b\s*:\s*\[\s*(\d+)\s*\]""")
    private val PROTECT_MODE_REGEX = Regex("""\bmProtectBatteryMode\b\s*:\s*(-?\d+)""")
    private val PROTECTION_THRESHOLD_REGEX = Regex("""\bmProtectionThreshold\b\s*:\s*(-?\d+)""")
    private val CYCLE_USAGE_REGEX = Regex("""\bmSavedBatteryUsage\b\s*:\s*\[\s*(-?\d+)\s*\]""")

    /** See [isPlausibleCycleRate]'s own doc for why these two specific numbers. */
    private const val MIN_PLAUSIBLE_CYCLES_PER_DAY = 0.1
    private const val MAX_PLAUSIBLE_CYCLES_PER_DAY = 3.0
}
