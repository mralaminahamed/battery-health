package com.alaminahamed.batteryhealth.data.settings

import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import javax.inject.Named
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where an [EffectiveDesignCapacity] value came from, for the UI to explain itself.
 *
 * [PowerProfile] is the device's own declaration, read from the platform's
 * `power_profile.xml` -- real data from the hardware in the user's hand rather than a
 * figure this project typed in. [Table] is a curated entry backed by two independent
 * published sources. Both are real; they differ in who is standing behind the number, and
 * the UI says which.
 */
enum class DesignCapacitySource { Override, Table, PowerProfile, None }

/**
 * The value [HealthEstimator][com.alaminahamed.batteryhealth.data.repo.HealthEstimator]
 * measures against, plus where it came from. `mah` is null only when [source] is [None] --
 * kept as one type rather than an `Int?` alongside a separately-derived source so a caller
 * can never observe a value with no matching provenance or vice versa.
 */
data class EffectiveDesignCapacity(val mah: Int?, val source: DesignCapacitySource) {
    companion object {
        val None = EffectiveDesignCapacity(null, DesignCapacitySource.None)
    }
}

@Singleton
class DesignCapacityProvider @Inject constructor(
    settings: SettingsStore,
    private val identity: DeviceIdentity,
    @Named("powerProfileCapacityMah") private val powerProfileMah: Int?,
) {
    /**
     * A user override always wins; the table is only the fallback. Exposed alongside
     * [designCapacityMah] (rather than that flow being derived by stripping this one's
     * source) so the settings UI can explain *why* it's showing what it's showing --
     * "the model table" vs. "your override" vs. inviting the user to set one -- without
     * re-deriving that from the bare value.
     */
    val effective: Flow<EffectiveDesignCapacity> =
        settings.designCapacityOverrideMah.map { override -> resolve(override, identity, powerProfileMah) }

    /** A user override always wins; the table is only the fallback. */
    val designCapacityMah: Flow<Int?> = effective.map { it.mah }

    companion object {
        /**
         * The precedence rule itself, pulled out as a pure function so it is provable on
         * the JVM without a `SettingsStore`/`Context` -- see this class's own test for
         * why. [effective] is a thin `Flow.map` around this; there is no second copy of
         * the rule anywhere else.
         *
         * Order, and the reasoning for it:
         *
         * 1. The user's override. They can see the number on the back of the battery or
         *    know they fitted a replacement cell; nothing this app derives outranks that.
         * 2. The curated table, when it has this device. Every row there is held to two
         *    independent published sources, which is a higher bar than the next entry
         *    clears.
         * 3. [powerProfileMah], the device's own `power_profile.xml` declaration. Real
         *    data from the hardware, and the only source that covers devices no table
         *    lists -- which is nearly all of them. It ranks below the table because the
         *    field is known to ship unfilled or wrong on some models: AOSP's template
         *    value is a placeholder of `2`, and OEMs do not always overwrite it. It has
         *    already passed [PowerProfileCapacity.interpret]'s plausibility check by the
         *    time it arrives here, so what remains is only the risk of a plausible but
         *    incorrect figure -- exactly the risk the table's two-source rule buys down.
         * 4. Nothing, reported as absence rather than filled in with a default.
         */
        internal fun resolve(
            overrideMah: Int?,
            identity: DeviceIdentity,
            powerProfileMah: Int?,
        ): EffectiveDesignCapacity = when {
            overrideMah != null -> EffectiveDesignCapacity(overrideMah, DesignCapacitySource.Override)
            else -> DesignCapacityTable.lookupMah(identity)
                ?.let { EffectiveDesignCapacity(it, DesignCapacitySource.Table) }
                ?: powerProfileMah?.let { EffectiveDesignCapacity(it, DesignCapacitySource.PowerProfile) }
                ?: EffectiveDesignCapacity.None
        }
    }
}
