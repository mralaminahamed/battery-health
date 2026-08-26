package com.alaminahamed.batteryhealth.data.vendor.discovery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import com.alaminahamed.batteryhealth.data.vendor.PowerProfileReader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs every reachable channel once and reports what this device actually offered.
 *
 * The premise is that a battery app cannot know in advance what a given phone exposes.
 * Vendors add undocumented broadcast extras, ship `power_profile.xml` with fields filled
 * or unfilled, and sit on different sides of platform flags that decide whether a property
 * is readable. Encoding assumptions about all of that produces an app that is confidently
 * wrong on hardware nobody tested. Asking produces one that is accurate everywhere and
 * says so when it has nothing.
 *
 * Nothing here needs a permission. The properties go through public `BatteryManager`
 * methods, the broadcast is sticky and world-readable, and the power profile is a resource
 * belonging to the `android` package.
 *
 * ## What is deliberately not probed
 *
 * `/sys/class/power_supply/battery/` is not attempted. It carries the figures this app
 * would most like — `charge_full_design`, `cycle_count`, `battery_soh` — but the
 * `untrusted_app` SELinux domain has not been able to read it for years, and the denial is
 * frequently covered by a `dontaudit` rule so it fails silently rather than reporting
 * anything. A probe that always returns the same "no" on every device teaches nothing and
 * would only pad the report with a row implying the app might one day get in.
 */
@Singleton
class BatteryDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val batteryManager: BatteryManager,
    private val identity: DeviceIdentity,
) {

    /**
     * One full sweep.
     *
     * Cheap enough to run on demand — a handful of binder calls, one sticky-broadcast read
     * and one XML parse — and deliberately not cached, so a report reflects the device as
     * it is now rather than as it was at process start. A platform flag can flip across an
     * OS update, and a stale answer about state of health is the one thing this sweep must
     * not produce.
     */
    fun sweep(): BatteryDiscoveryReport = BatteryDiscoveryReport(
        results = buildList {
            addAll(properties())
            addAll(broadcastExtras())
            addAll(powerProfile())
        },
    )

    /** The device this report describes, so two reports are never confused for each other. */
    val describes: DeviceIdentity get() = identity

    /**
     * `getLongProperty` rather than `getIntProperty`: several properties are genuinely
     * wider than an Int (`ENERGY_COUNTER` in nWh, the date fields as epoch values), and
     * reading them through the narrow accessor would truncate a real value into a
     * plausible wrong one. The long accessor returns `Long.MIN_VALUE` for unsupported,
     * which is what [ProbeSentinels.UNSUPPORTED] matches.
     */
    private fun properties(): List<ProbeResult> =
        BatteryPropertyProbe { id -> batteryManager.getLongProperty(id) }.probe()

    /**
     * Every key the battery-changed broadcast carries, including ones no AOSP constant
     * names. This is the only channel that can surface something undocumented, which is
     * the whole reason it is enumerated rather than queried key by key.
     *
     * `registerReceiver` with a null receiver returns the current sticky intent without
     * subscribing to anything, so this neither leaks a receiver nor keeps the app awake.
     */
    private fun broadcastExtras(): List<ProbeResult> {
        val intent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val extras = intent?.extras ?: return emptyList()
        val seen = mutableMapOf<String, String?>()
        for (key in extras.keySet()) {
            // `Bundle.get` is the only accessor that works without knowing the type, and
            // the values here are a mix of ints, strings and booleans set by whichever
            // vendor built the image. Anything that throws on read is recorded as absent
            // rather than aborting the enumeration.
            seen[key] = try {
                @Suppress("DEPRECATION")
                extras.get(key)?.toString()
            } catch (t: Throwable) {
                null
            }
        }
        return BatteryExtrasProbe.resultsFrom(seen)
    }

    /**
     * Every `battery.*` item in the platform's power profile, not just the capacity.
     *
     * The neighbouring fields are worth recording even though nothing consumes them yet:
     * a device that fills in `battery.capacity` but leaves the rest at AOSP defaults looks
     * very different from one whose whole profile is populated, and that is a useful
     * signal about how much to trust the capacity figure itself.
     */
    private fun powerProfile(): List<ProbeResult> =
        PowerProfileReader(context).batteryItems()
            .toSortedMap()
            .map { (name, value) ->
                ProbeResult(
                    channel = ProbeChannel.PowerProfile,
                    key = name,
                    outcome = value?.takeIf { it.isNotBlank() }
                        ?.let { ProbeOutcome.Value(it) }
                        ?: ProbeOutcome.Absent,
                )
            }
}
