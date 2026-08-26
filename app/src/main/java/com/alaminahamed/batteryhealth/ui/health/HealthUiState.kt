package com.alaminahamed.batteryhealth.ui.health

import com.alaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
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
     * that came from the model table or the user's override. Defaults to
     * [EffectiveDesignCapacity.None] only as the cold-start placeholder before the real
     * flow first emits -- production always replaces it immediately.
     */
    val designCapacity: EffectiveDesignCapacity = EffectiveDesignCapacity.None,
    /**
     * Drives [UnlockCard][com.alaminahamed.batteryhealth.ui.components.UnlockCard].
     * Defaults to [PrivilegedAvailability.Unavailable] only as the cold-start placeholder
     * before `AdbGateway`'s real state first emits -- the same convention
     * [designCapacity] already uses above.
     */
    val privilegedAvailability: PrivilegedAvailability = PrivilegedAvailability.Unavailable,
    /**
     * True only while [privilegedAvailability] is [PrivilegedAvailability.Ready] and the
     * most recent privileged dump attempt still came back empty -- see
     * `BatteryRepository.privilegedDumpFailed`'s own doc. Drives `UnlockCard`'s
     * otherwise-unreachable "Ready but the read failed, retry" case.
     */
    val privilegedDumpFailed: Boolean = false,
    /**
     * Mirrors `SettingsStore.unlockCardDismissed`. Defaults to false only as the
     * cold-start placeholder before the real flow first emits, the same convention the
     * other mirrored fields here use.
     */
    val unlockCardDismissed: Boolean = false,
    /**
     * Whether `BATTERY_STATS` is held. Not a battery reading, so it does not belong on the
     * snapshot -- but the unlock card cannot tell a useful offer from a pointless one
     * without it. Defaults to false, the state of every install that has not run the
     * grant.
     */
    val batteryStatsGranted: Boolean = false,
    /**
     * Whether this build has a privileged transport compiled in at all.
     *
     * False in the Play flavour, which ships no adb or root code and no INTERNET
     * permission. The UI reads it rather than inferring from an availability that would
     * simply never become Ready, because those two states want opposite things said: one
     * is "run this command", the other is "this build cannot do that, and does not need
     * to".
     */
    val privilegedTierSupported: Boolean = true,
) {
    /**
     * A value the platform reports directly beats one this app inferred, but beyond
     * that, absence is not all the same kind of absence. This used to be moot in
     * practice: `BatteryRepository` set `stateOfHealthPct` to `NeedsPrivilegedAccess`
     * unconditionally (this device's own unprivileged `BatteryManager` can never report
     * it, signature-permission-gated), so `framework is Reading.Available` was never
     * true and every other case fell straight through to `measured` alone. That is no
     * longer true now that the privileged tier exists: once it is connected, a real
     * dump makes `framework` genuinely `Reading.Available` (Samsung's ASOC, sourced
     * `Privileged`), and this precedence is what a real device now exercises rather than
     * only a hypothetical one. Before that fix landed, the fallthrough also silently
     * discarded `NeedsPrivilegedAccess` whenever `measured` was `Unsupported` — nearly every
     * device, since at the time the design-capacity table held ten Samsung entries and
     * there was no override UI. Both of those have since changed (the table covers more
     * models, reads the device's own `power_profile.xml`, and Settings offers an
     * override), which narrows how often that path is taken but does not change the
     * precedence argument below — and rendered "Not available on this device" one row
     * above a "Needs privileged
     * access" line about the exact same underlying data. Explicit precedence fixes both:
     * Available beats NotYetMeasured beats NeedsPrivilegedAccess beats Unsupported, so a
     * real measurement still wins when one exists, and a `NeedsPrivilegedAccess` is never
     * displaced by a plain `Unsupported`.
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
        Reading.Unsupported -> 0
    }
}
