package com.alaminahamed.batteryhealth.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Where an [EffectiveDesignCapacity] value came from, for the UI to explain itself. */
enum class DesignCapacitySource { Override, Table, None }

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
    @Named("deviceModel") private val model: String,
) {
    /**
     * A user override always wins; the table is only the fallback. Exposed alongside
     * [designCapacityMah] (rather than that flow being derived by stripping this one's
     * source) so the settings UI can explain *why* it's showing what it's showing --
     * "the model table" vs. "your override" vs. inviting the user to set one -- without
     * re-deriving that from the bare value.
     */
    val effective: Flow<EffectiveDesignCapacity> = settings.designCapacityOverrideMah.map { override ->
        when {
            override != null -> EffectiveDesignCapacity(override, DesignCapacitySource.Override)
            else -> DesignCapacityTable.lookup(model)
                ?.let { EffectiveDesignCapacity(it, DesignCapacitySource.Table) }
                ?: EffectiveDesignCapacity.None
        }
    }

    /** A user override always wins; the table is only the fallback. */
    val designCapacityMah: Flow<Int?> = effective.map { it.mah }
}
