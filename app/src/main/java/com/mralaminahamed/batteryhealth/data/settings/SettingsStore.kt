package com.mralaminahamed.batteryhealth.data.settings

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mralaminahamed.batteryhealth.data.framework.CurrentScale
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

    /**
     * The device's CURRENT_NOW unit scale, once `CurrentScaleDetector.fromCounterAgreement`
     * has confirmed it against a real charge-counter delta. Absent until then -- there is no
     * default here, unlike `recorderEnabled`, because a guessed default would be exactly the
     * invented data this whole mechanism exists to avoid. `BatteryManagerSource` falls back
     * to a per-reading magnitude guess for as long as this stays null.
     *
     * Never cleared once set, and re-written (not skipped) every time a later session
     * validates it again: the scale is a hardware/firmware characteristic that will not
     * change between charges on the same device, so there is no "already known, don't
     * re-check" gate here -- the cost of re-confirming the same answer is one cheap,
     * idempotent write, and it is what lets a genuine change (an OS update touching the
     * HAL) self-correct instead of being stuck behind a stale value forever.
     */
    val currentScale: Flow<CurrentScale?> = context.dataStore.data
        .map { prefs -> prefs[CURRENT_SCALE]?.let { stored -> runCatching { CurrentScale.valueOf(stored) }.getOrNull() } }
        .distinctUntilChanged()

    /**
     * The TCP port the ADB client dials on loopback. User-overridable because they choose
     * the port when running `adb tcpip N`; absent devices default to the standard port 5555.
     */
    val adbPort: Flow<Int> =
        context.dataStore.data.map { it[ADB_PORT] ?: 5555 }.distinctUntilChanged()

    /**
     * Whether the user has previously granted root access to the privileged gateway.
     * Load-bearing default of false: probing by running su raises Magisk's grant dialog,
     * and defaulting true here would trigger that dialog on first launch before the user
     * has asked for anything — hostile in a battery app. The flag exists so the gateway
     * skips a probe the user has already answered, never triggering one they haven't.
     */
    val rootPreviouslyGranted: Flow<Boolean> =
        context.dataStore.data.map { it[ROOT_PREVIOUSLY_GRANTED] ?: false }.distinctUntilChanged()

    suspend fun setCurrentScale(scale: CurrentScale) {
        context.dataStore.edit { it[CURRENT_SCALE] = scale.name }
    }

    suspend fun setAdbPort(port: Int) {
        context.dataStore.edit { it[ADB_PORT] = port }
    }

    suspend fun setRootPreviouslyGranted(granted: Boolean) {
        context.dataStore.edit { it[ROOT_PREVIOUSLY_GRANTED] = granted }
    }

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
        val CURRENT_SCALE = stringPreferencesKey("current_scale")
        val ADB_PORT = intPreferencesKey("adb_port")
        val ROOT_PREVIOUSLY_GRANTED = booleanPreferencesKey("root_previously_granted")
    }
}
