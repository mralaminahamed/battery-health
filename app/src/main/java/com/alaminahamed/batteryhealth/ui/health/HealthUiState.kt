package com.alaminahamed.batteryhealth.ui.health

import com.alaminahamed.batteryhealth.data.settings.EffectiveDesignCapacity
import com.alaminahamed.batteryhealth.domain.BatterySnapshot
import com.alaminahamed.batteryhealth.domain.HealthReport
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.map

/**
 * What the health card should say underneath the headline while there is no measurement
 * yet.
 *
 * This exists because the screen used to say "Needs 3 full charge sessions" whenever
 * `measured` was [Reading.NotYetMeasured], regardless of whether anything was actually
 * recording. Running the app on a real device showed the consequence plainly: the
 * headline promised progress towards a number while the recorder switch two cards below
 * it was off, so no session would ever be counted and that line would have stood there
 * forever.
 *
 * The distinction is the same one this codebase draws everywhere else -- an absence has a
 * reason, and the reasons are not interchangeable. "Waiting for charge sessions" and
 * "nothing is recording" both mean there is no number, and only one of them is something
 * the user can act on.
 */
enum class MeasurementNote {
    /** Recording, and simply short of the sessions the estimator needs. */
    NeedsSessions,

    /** The recorder is switched off, so no session will ever be counted. */
    NotRecording,

    /**
     * The recorder is switched on but its service was refused a start. `recorderEnabled`
     * records intent rather than whether anything runs, so this case reads true while
     * nothing is being recorded -- battery saver refusing the foreground service is the
     * way this happens in practice, observed on a real device.
     */
    RecordingBlocked,

    /** Nothing to say: there is already a measurement, or one is impossible anyway. */
    None,
}

data class HealthUiState(
    val snapshot: BatterySnapshot?,
    val measured: Reading<HealthReport>,
    /** Mirrors `SettingsStore.recorderEnabled`; opt-in, defaults off. */
    val recorderEnabled: Boolean = false,
    /**
     * True only right after an enable attempt was refused (e.g.
     * `ForegroundServiceStartNotAllowedException`). `recorderEnabled` can still read
     * true in that case -- the flag records intent, not whether the service is
     * currently running -- so this is what lets the screen say "on, but not actually
     * recording" instead of silently agreeing with the flag.
     */
    val recorderStartFailed: Boolean = false,
    /** True once the user has denied POST_NOTIFICATIONS (API 33+) for this session. */
    val notificationsDenied: Boolean = false,
    /**
     * The design capacity `HealthEstimator` is actually measuring against, and whether
     * that came from the model table or the device's own `power_profile.xml`. Defaults to
     * [EffectiveDesignCapacity.None] only as the cold-start placeholder before the real
     * flow first emits -- production always replaces it immediately.
     */
    val designCapacity: EffectiveDesignCapacity = EffectiveDesignCapacity.None,
) {
    /**
     * A value the platform reports directly beats one this app inferred. `framework` is
     * only ever `Available` (a real ASOC figure, on a build/device where
     * `stateOfHealthPublic()` is set) or `Unsupported`/`NeedsPrivilegedAccess` now that
     * there is no privileged shell tier behind it -- see `BatteryRepository.snapshots`'s
     * own doc. `measuredPct` still needs its own place in the precedence for the ordinary
     * case: most devices never set that platform flag, so `framework` stays absent and the
     * measured trend is what the headline actually shows.
     */
    val headlinePct: Reading<Int>
        get() {
            val framework = snapshot?.stateOfHealthPct ?: Reading.Unsupported
            val measuredPct = measured.map { it.healthPct }
            return if (framework.precedence() >= measuredPct.precedence()) framework else measuredPct
        }

    /**
     * Why there is no measured percentage yet, or [MeasurementNote.None] when that
     * question does not arise.
     *
     * Derived here rather than in the composable so the rule is provable on the JVM, and
     * so the screen cannot drift back into stating one of these while the state says
     * another -- which is exactly how the original defect got in.
     */
    val measurementNote: MeasurementNote
        get() = when {
            measured !is Reading.NotYetMeasured -> MeasurementNote.None
            recorderStartFailed -> MeasurementNote.RecordingBlocked
            !recorderEnabled -> MeasurementNote.NotRecording
            else -> MeasurementNote.NeedsSessions
        }

    private fun Reading<*>.precedence(): Int = when (this) {
        is Reading.Available -> 3
        Reading.NotYetMeasured -> 2
        Reading.NeedsPrivilegedAccess -> 1
        // Never actually reached here: framework is a platform Reading<Int> and measured
        // comes from HealthEstimator, neither of which ever produces NeedsUsageAccess
        // (that absence belongs to the Apps screen's estimate, not this card). Ranked
        // beside NeedsPrivilegedAccess rather than left unhandled: both are an absence
        // with a specific, actionable remedy this comparison just does not happen to name.
        Reading.NeedsUsageAccess -> 1
        Reading.Unsupported -> 0
    }
}
