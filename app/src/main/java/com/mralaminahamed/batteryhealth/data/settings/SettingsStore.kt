package com.mralaminahamed.batteryhealth.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsStore @Inject constructor(private val context: Context) {

    val designCapacityOverrideMah: Flow<Int?> =
        context.dataStore.data.map { it[DESIGN_CAPACITY_OVERRIDE] }

    /**
     * The charge recorder is opt-in. A battery app that installs a permanent
     * notification and continuous wakeups without being asked undermines its premise.
     */
    val recorderEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[RECORDER_ENABLED] ?: false }

    suspend fun setDesignCapacityOverride(mah: Int?) {
        context.dataStore.edit { prefs ->
            if (mah == null) prefs.remove(DESIGN_CAPACITY_OVERRIDE) else prefs[DESIGN_CAPACITY_OVERRIDE] = mah
        }
    }

    suspend fun setRecorderEnabled(enabled: Boolean) {
        context.dataStore.edit { it[RECORDER_ENABLED] = enabled }
    }

    private companion object {
        val DESIGN_CAPACITY_OVERRIDE = intPreferencesKey("design_capacity_override_mah")
        val RECORDER_ENABLED = booleanPreferencesKey("recorder_enabled")
    }
}
