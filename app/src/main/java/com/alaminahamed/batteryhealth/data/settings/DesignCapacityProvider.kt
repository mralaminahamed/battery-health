package com.alaminahamed.batteryhealth.data.settings

import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import javax.inject.Named
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
 *
 * [Override] is retained as a case here (rather than removed from the enum) even though
 * nothing in this app writes one any more -- see [DesignCapacityProvider]'s own doc for
 * why the setting was removed. Keeping the case costs nothing and keeps this file's
 * history honest about what a stored preference from an older install still means if one
 * is ever found; [resolve] itself never produces it.
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

/**
 * Resolves design capacity from data the device itself supplies, with no field for the
 * user to type a number into.
 *
 * This used to start with a user override, entered from a Settings dialog. That is gone:
 * the owner's decision for this app is that it asks for nothing by typing -- only for
 * permissions, granted the normal Android way -- and a free-text capacity field was
 * exactly the kind of input that decision rules out. Removing it is a real cost, not a
 * free simplification: a device this app cannot identify (absent from [DesignCapacityTable])
 * *and* whose `power_profile.xml` is unreadable or implausible now has no design capacity
 * at all, and therefore no measured health trend, with no remedy the user can reach from
 * inside this app. Every device this was verified against (the curated table, or a
 * `power_profile.xml` that passes [PowerProfileCapacity]'s plausibility check) is
 * unaffected; the cost lands only on an unlisted device with an unreadable profile.
 *
 * What is left is device-and-data-driven only: the curated table first, then the device's
 * own declaration, then nothing. See [resolve]'s own doc for why that order is preserved
 * exactly as it was rather than swapped now that the override sitting above both is gone.
 */
@Singleton
class DesignCapacityProvider @Inject constructor(
    private val identity: DeviceIdentity,
    @Named("powerProfileCapacityMah") private val powerProfileMah: Int?,
) {
    /**
     * Exposed as one object (rather than a bare `Int?` derived elsewhere) so the Health and
     * Settings screens can explain *why* they're showing what they're showing -- "the model
     * table" vs. "reported by this device" vs. "not set" -- without re-deriving that from
     * the bare value.
     */
    val effective: Flow<EffectiveDesignCapacity> = flowOf(resolve(identity, powerProfileMah))

    val designCapacityMah: Flow<Int?> = effective.map { it.mah }

    companion object {
        /**
         * The precedence rule itself, pulled out as a pure function so it is provable on
         * the JVM without a `DeviceIdentity` wrapper doing anything surprising -- see this
         * class's own test for why.
         *
         * Order, and the reasoning for it:
         *
         * 1. The curated table, when it has this device. Every row there is held to two
         *    independent published sources, which is a higher bar than the next entry
         *    clears.
         * 2. [powerProfileMah], the device's own `power_profile.xml` declaration. Real
         *    data from the hardware, and the only source that covers devices no table
         *    lists -- which is nearly all of them. It ranks below the table because the
         *    field is known to ship unfilled or wrong on some models: AOSP's template
         *    value is a placeholder of `2`, and OEMs do not always overwrite it. It has
         *    already passed [PowerProfileCapacity.interpret]'s plausibility check by the
         *    time it arrives here, so what remains is only the risk of a plausible but
         *    incorrect figure -- exactly the risk the table's two-source rule buys down.
         * 3. Nothing, reported as absence rather than filled in with a default.
         *
         * This order is deliberately unchanged from before the user override was removed
         * from the top of it: the table's two-source bar is still a higher bar than an
         * unvalidated device declaration clears, so preferring the table over
         * `power_profile.xml` remains the right call, not merely a leftover one.
         */
        internal fun resolve(
            identity: DeviceIdentity,
            powerProfileMah: Int?,
        ): EffectiveDesignCapacity =
            DesignCapacityTable.lookupMah(identity)
                ?.let { EffectiveDesignCapacity(it, DesignCapacitySource.Table) }
                ?: powerProfileMah?.let { EffectiveDesignCapacity(it, DesignCapacitySource.PowerProfile) }
                ?: EffectiveDesignCapacity.None
    }
}
