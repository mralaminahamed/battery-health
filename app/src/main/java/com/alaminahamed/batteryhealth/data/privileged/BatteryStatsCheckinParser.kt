package com.alaminahamed.batteryhealth.data.privileged

/**
 * The two facts [BatteryStatsCheckinParser] extracts from `dumpsys batterystats
 * --checkin`, kept as independent maps rather than one joined structure: a uid can have
 * power with no known package (uid `0` on the real fixture this was built against -- root
 * drew power but named no package), or packages with no power recorded yet, and neither
 * map is allowed to invent an entry in the other to paper over that. Whatever needs both
 * (see `AppPowerAggregator`) does its own lookup with its own explicit fallback for a miss,
 * the same discipline [ParsedBatteryDump] already applies per individual field.
 */
data class ParsedBatteryStats(
    /** uid -> estimated milliamp-hours drawn since `batterystats` was last reset. Only
     * uids `--checkin` actually reported a "pwi,uid" row for are present; a uid with no
     * entry here is not "zero", it is "this dump said nothing about it". */
    val uidPowerMah: Map<Int, Double>,
    /** uid -> every package `--checkin`'s own uid dictionary attributes to it, in the
     * order the dump listed them. Usually one entry; occasionally none (see
     * [uidPowerMah]'s doc); occasionally dozens for a shared `android:sharedUserId` --
     * uid `1000` alone owns 82 on the fixture this was built against. */
    val uidPackages: Map<Int, List<String>>,
)

/**
 * Pure function over `dumpsys batterystats --checkin`'s output. No Android types, so this
 * is JVM-tested directly against a real capture
 * (`app/src/test/resources/batterystats-checkin-sm-a356e.csv`, 525KB from the same device
 * `DumpsysBatteryParser` was built against) rather than a hand-written fixture that could
 * drift from what `--checkin` actually prints.
 *
 * `--checkin` specifically, not the human-readable `dumpsys batterystats` (no flag), is
 * the deliberate choice here -- the opposite trade-off from [DumpsysBatteryParser]'s own
 * regex-over-prose approach, and for a reason specific to this data rather than a
 * blanket rule: `--checkin`'s format is comma-separated and versioned precisely so tools
 * can parse it without re-deriving column meaning from prose every time, and this parser
 * has to find two row shapes among roughly two thousand lines and ninety-plus per-uid
 * rows on a single real capture -- a volume where a human-readable label drifting
 * (reordered words, a renamed section heading) is a much larger silent-breakage surface,
 * multiplied by every row, than one row type's fixed column position drifting. The prose
 * dump `dumpsys battery` parses is a handful of named fields in a small, human-curated
 * block; that asymmetry, not a rule that one format is always better, is why the two
 * parsers in this package make opposite choices.
 *
 * Two independent row shapes are pulled out of the *same* single pass over the dump
 * (`lineSequence()`, not `split("\n")` materialising the whole 525KB as a `List<String>`
 * up front, and not a separate scan per row type): every field on every row is optional
 * in the same spirit as [DumpsysBatteryParser] -- a malformed or missing field degrades
 * only the one row it appears on, never the row type, and a bad row of one type never
 * touches the other type's map.
 *
 *  - **Per-uid power**: `<v>,<uid>,l,pwi,uid,<mAh>,...` -- field 4 (`pwi`) marks a power
 *    row and field 5 (`uid`) marks it as attributed to a *uid as a whole*, as opposed to
 *    the system-wide-by-hardware-component breakdown that also uses `pwi` but with field
 *    5 naming a component instead (`cpu`, `video`, `camera`, ...) and field 2 always `0`.
 *    Mixing the two would silently inflate the "per-app" count with rows that were never
 *    about any single uid.
 *  - **uid -> package**: `<v>,0,i,uid,<uid>,<packageName>` -- field 4 (`uid`) marks this
 *    row shape; field 5 is the uid, field 6 the package name it owns. Accumulated into a
 *    list per uid, never overwritten, because a shared uid legitimately owns many.
 */
object BatteryStatsCheckinParser {

    fun parse(dump: String): ParsedBatteryStats {
        val uidPowerMah = LinkedHashMap<Int, Double>()
        val uidPackages = LinkedHashMap<Int, MutableList<String>>()

        for (line in dump.lineSequence()) {
            if (line.isBlank()) continue
            val fields = line.split(',')
            if (fields.size < REQUIRED_FIELDS) continue

            when (fields[FIELD_ROW_TYPE]) {
                POWER_ROW_TYPE -> if (fields[FIELD_POWER_SUBTYPE] == POWER_SUBTYPE_UID) {
                    val uid = fields[FIELD_POWER_UID].toIntOrNull() ?: continue
                    val mAh = fields[FIELD_POWER_MAH].toDoubleOrNull() ?: continue
                    uidPowerMah[uid] = mAh
                }

                UID_ROW_TYPE -> {
                    val uid = fields[FIELD_UID_ROW_UID].toIntOrNull() ?: continue
                    val packageName = fields.getOrNull(FIELD_UID_ROW_PACKAGE)
                        ?.takeIf { it.isNotBlank() }
                        ?: continue
                    uidPackages.getOrPut(uid) { mutableListOf() }.add(packageName)
                }
            }
        }

        return ParsedBatteryStats(uidPowerMah = uidPowerMah, uidPackages = uidPackages)
    }

    // 0-indexed positions into a comma-split line. Named per row shape rather than one
    // shared set of constants: field 4 means "uid" for one row shape's discriminator and
    // "the uid number" for the other's payload -- the same column index means two
    // different things depending which row type it is read from, so collapsing these
    // into shared names would be the general-rule-over-specific mistake this codebase
    // keeps being warned about, just in constant-naming form.
    private const val REQUIRED_FIELDS = 6

    private const val FIELD_ROW_TYPE = 3
    private const val POWER_ROW_TYPE = "pwi"
    private const val UID_ROW_TYPE = "uid"

    private const val FIELD_POWER_UID = 1
    private const val FIELD_POWER_SUBTYPE = 4
    private const val POWER_SUBTYPE_UID = "uid"
    private const val FIELD_POWER_MAH = 5

    private const val FIELD_UID_ROW_UID = 4
    private const val FIELD_UID_ROW_PACKAGE = 5
}
