package com.mralaminahamed.batteryhealth.data.privileged

import java.time.LocalDate

/**
 * Every field independently optional, and `null` on any of them means exactly one thing:
 * this dump did not contain a line this parser recognised for that field. Never a
 * default, never a zero standing in for absence -- `BatteryRepository` is the only place
 * that decides what a `null` here should mean for a `Reading` (`Unsupported` once a real
 * dump was in hand, `NeedsShizuku` when there was no dump to try at all), and it needs the
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

    fun parse(dump: String): ParsedBatteryDump = ParsedBatteryDump(
        asocPct = intField(dump, ASOC_REGEX),
        bsohPct = intField(dump, BSOH_REGEX),
        firstUseDateEpochDay = intField(dump, FIRST_USE_DATE_REGEX)?.let(::packedDateToEpochDay),
        protectBatteryModeEnabled = intField(dump, PROTECT_MODE_REGEX)?.let { it != 0 },
        protectionThresholdPct = intField(dump, PROTECTION_THRESHOLD_REGEX),
    )

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
}
