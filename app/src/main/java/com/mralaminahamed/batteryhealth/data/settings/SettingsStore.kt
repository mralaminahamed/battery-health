package com.mralaminahamed.batteryhealth.data.settings

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mralaminahamed.batteryhealth.sampling.ChargeRecorderService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsStore @Inject constructor(private val context: Context) {

    // distinctUntilChanged because both flows derive from the same underlying
    // dataStore.data: writing either key would otherwise re-emit an unchanged value on the
    // other, needlessly re-running the design-capacity lookup downstream.
    val designCapacityOverrideMah: Flow<Int?> =
        context.dataStore.data.map { it[DESIGN_CAPACITY_OVERRIDE] }.distinctUntilChanged()

    /**
     * The charge recorder is opt-in. A battery app that installs a permanent
     * notification and continuous wakeups without being asked undermines its premise.
     */
    val recorderEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[RECORDER_ENABLED] ?: false }.distinctUntilChanged()

    suspend fun setDesignCapacityOverride(mah: Int?) {
        context.dataStore.edit { prefs ->
            if (mah == null) prefs.remove(DESIGN_CAPACITY_OVERRIDE) else prefs[DESIGN_CAPACITY_OVERRIDE] = mah
        }
    }

    /**
     * The single place this flag is ever written. Enabling starts the recorder's
     * foreground service from here -- the app's own foreground call site (a settings
     * toggle), which is what lets `ChargeRecorderService.start` call
     * `startForegroundService` without hitting the background-start restriction that
     * broke the two previous designs. Disabling does *not* stop the service directly:
     * a running service watches this same flag and stops itself once it reads false.
     * An external `stopService()` call here was tried first and rejected -- it can race
     * ahead of a just-started service's own `onCreate()` and crash with
     * `ForegroundServiceDidNotStartInTimeException` on a rapid enable-then-disable, which
     * happened for real (see `ChargeRecorderService`'s class doc for the on-device
     * evidence).
     *
     * The flag is written `true` regardless of whether the start call below actually
     * succeeds: a refusal is a transient process-state problem, not a change of intent,
     * and the flag staying on is what lets the Health screen's own re-arm-on-launch
     * retry later from a context the start is unambiguously permitted in. The return
     * value exists so a caller (the Health screen) can reflect an immediate refusal in
     * the UI instead of it disappearing into `Log.w` with nothing visible anywhere.
     */
    suspend fun setRecorderEnabled(enabled: Boolean): Boolean {
        context.dataStore.edit { it[RECORDER_ENABLED] = enabled }
        return if (enabled) ChargeRecorderService.start(context) else true
    }

    /**
     * Resets every key. Exists only so the instrumented test can be hermetic: DataStore is
     * reached through a fixed-name Context delegate, so a test has no separate file to
     * write and must clean up after itself or leave the user's real settings altered.
     * Clearing resets `recorderEnabled` to its false default, which a running service's
     * own watcher reacts to the same way `setRecorderEnabled(false)` does -- no separate
     * stop call is needed here either.
     */
    @VisibleForTesting
    suspend fun clearForTesting() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val DESIGN_CAPACITY_OVERRIDE = intPreferencesKey("design_capacity_override_mah")
        val RECORDER_ENABLED = booleanPreferencesKey("recorder_enabled")
    }
}
