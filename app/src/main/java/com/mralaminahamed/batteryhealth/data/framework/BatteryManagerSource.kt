package com.mralaminahamed.batteryhealth.data.framework

import android.os.BatteryManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.VisibleForTesting
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Point reads of BatteryManager's integer properties, gated by the capability set so
 * an unsupported property yields Unsupported rather than a sentinel dressed as data.
 */
@Singleton
class BatteryManagerSource @Inject constructor(
    private val batteryManager: BatteryManager,
    private val capabilities: @JvmSuppressWildcards Set<BatteryProperty>,
    settingsStore: SettingsStore,
) {
    /**
     * The scale [CurrentScaleDetector.fromCounterAgreement] has confirmed, mirrored from
     * `SettingsStore` into a plain field because `currentUa()` is called synchronously (the
     * Live screen and the sampler both need an answer on this call, not after a suspend
     * point). Null until the first session that moves enough charge to validate it -- see
     * `currentUa()` for what happens while it is null. This is a live-forever collector on a
     * `@Singleton` with the app's own lifetime, the same pattern `ChargeRecorderService`
     * uses for `recorderEnabled`: whichever component last wrote a validated scale to
     * `SettingsStore`, this field catches up shortly after, asynchronously.
     */
    @Volatile private var validatedScale: CurrentScale? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settingsStore.currentScale.collect { validatedScale = it }
        }
    }

    fun chargeCounterUah(): Reading<Long> = read(BatteryProperty.ChargeCounter) { it.toLong() }

    /**
     * True microamps, never the device's untouched register value. Android documents
     * CURRENT_NOW as microamps; some OEMs (the Galaxy A35 this was found on, among others)
     * report milliamps instead, so a raw passthrough here is exactly the defect this method
     * exists to not repeat.
     *
     * Priority order, most to least trustworthy, and the general guess is never allowed to
     * override the specific measurement once one exists: a counter-validated [validatedScale]
     * wins unconditionally over the current reading's own magnitude, because it is a
     * measurement rather than a heuristic; failing that, [CurrentScaleDetector.fromMagnitude]
     * gives an immediate answer when the reading is unambiguous; failing that, this returns
     * Unsupported -- no invented data, even temporarily.
     *
     * The sentinel check below shares [BatteryProperty.isPlausibleReading] with
     * [CapabilityProbe] and [read] rather than re-deriving it: [Int.MIN_VALUE] is rejected
     * before any scale is even considered, let alone multiplied through, but that alone is
     * not the whole rule -- see [BatteryProperty.isPlausibleReading]'s doc for why folding
     * every property's sentinel into one check here would repeat the exact defect Task 4
     * fixed one layer up, in [CapabilityProbe].
     */
    fun currentUa(): Reading<Int> {
        if (BatteryProperty.CurrentNow !in capabilities) return Reading.Unsupported
        val raw = batteryManager.getIntProperty(BatteryProperty.CurrentNow.id)
        if (!BatteryProperty.CurrentNow.isPlausibleReading(raw)) return Reading.Unsupported
        val scale = validatedScale ?: CurrentScaleDetector.fromMagnitude(raw) ?: return Reading.Unsupported
        val trueMicroamps = scale.toMicroamps(raw)
        // Guards against a corrupt or wildly implausible register value silently wrapping
        // into a different, plausible-looking Int when narrowed below -- realistic phone
        // currents (single-digit amps) never approach this bound at either scale.
        if (trueMicroamps !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return Reading.Unsupported
        return Reading.Available(trueMicroamps.toInt(), Source.Framework)
    }

    /**
     * The untouched CURRENT_NOW register value: capability- and sentinel-gated exactly like
     * every other reading here, but deliberately never scaled. Not a claim about units --
     * callers must not treat this as microamps or display it -- kept only so a completed
     * session can later cross-validate the device's real scale against the charge counter
     * (see [CurrentScaleDetector.fromCounterAgreement] and `SessionAggregator`'s
     * `rawCurrentIntegral`). Feeding that check a value [currentUa] had already scaled would
     * let a correct per-reading magnitude guess get mistaken for confirmation of the wrong
     * scale.
     */
    fun currentRawUnits(): Reading<Int> = read(BatteryProperty.CurrentNow) { it }

    /**
     * `BatteryManager#computeChargeTimeRemaining` is API 28
     * ([Build.VERSION_CODES.P]), guarded explicitly rather than called unconditionally:
     * minSdk is 26, chosen deliberately elsewhere in this app, and is not being raised
     * just to make this one check unnecessary. Below API 28 the call itself does not
     * exist on the device -- calling it anyway throws `NoSuchMethodError` out of
     * whichever coroutine first collects a snapshot, which is every device on Android
     * 8.0/8.1 that this app's own manifest claims to support. `Reading.Unsupported` is
     * the honest answer there, not a bug being papered over: the platform genuinely
     * cannot supply this figure on those OS versions.
     *
     * Do not "simplify" this guard away without also raising minSdk -- that trade was
     * already considered and rejected.
     */
    fun chargeTimeRemainingMs(): Reading<Long> {
        if (!isChargeTimeRemainingSupported(Build.VERSION.SDK_INT)) return Reading.Unsupported
        val remaining = batteryManager.computeChargeTimeRemaining()
        return if (remaining > 0) {
            Reading.Available(remaining, Source.Framework)
        } else {
            Reading.Unsupported
        }
    }

    /**
     * The sentinel rule lives on [BatteryProperty] itself (see
     * [BatteryProperty.isPlausibleReading]) and is shared with [CapabilityProbe], which
     * samples each property once at startup. This is the same rule applied again on an
     * ordinary later read, after the property already passed that probe -- not a global
     * `raw == Int.MIN_VALUE` check, which would silently disable [BatteryProperty.ChargeCounter]
     * every time it transiently reads its own documented -1 sentinel, the exact defect
     * Task 4 fixed in the probe itself.
     */
    private fun <T> read(property: BatteryProperty, transform: (Int) -> T): Reading<T> {
        if (property !in capabilities) return Reading.Unsupported
        val raw = batteryManager.getIntProperty(property.id)
        if (!property.isPlausibleReading(raw)) return Reading.Unsupported
        return Reading.Available(transform(raw), Source.Framework)
    }

    companion object {
        /**
         * Pure so the minSdk-26 boundary is provable on the JVM without a real API
         * 26/27 device. [Build.VERSION_CODES.P] is 28; referenced by its constant name
         * for the reader, not the raw literal. `@ChecksSdkIntAtLeast` is what lets lint's
         * own `NewApi` check see through this helper as a version gate at the call site
         * in [chargeTimeRemainingMs] -- without it, lint cannot tell this function apart
         * from an arbitrary `Boolean`-returning method and keeps flagging the guarded
         * call as unconditional.
         */
        @VisibleForTesting
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
        fun isChargeTimeRemainingSupported(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.P
    }
}
