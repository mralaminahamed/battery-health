package com.mralaminahamed.batteryhealth.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DesignCapacityProvider @Inject constructor(
    settings: SettingsStore,
    @Named("deviceModel") private val model: String,
) {
    /** A user override always wins; the table is only the fallback. */
    val designCapacityMah: Flow<Int?> = settings.designCapacityOverrideMah.map { override ->
        override ?: DesignCapacityTable.lookup(model)
    }
}
