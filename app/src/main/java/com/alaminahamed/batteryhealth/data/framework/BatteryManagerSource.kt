package com.alaminahamed.batteryhealth.data.framework

import android.os.BatteryManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.VisibleForTesting
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
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
     * point). Null until the first session that moves enough charge to validate it, or
     * until [scaleLoaded] becomes true for the first time -- see [scaleLoaded] and
     * `currentUa()` for what happens while either is still unresolved. This is a
     * live-forever collector on a `@Singleton` with the app's own lifetime, the same pattern
     * `ChargeRecorderService` uses for `recorderEnabled`: whichever component last wrote a
     * validated scale to `SettingsStore`, this field catches up shortly after, asynchronously.
     */
    @Volatile private var validatedScale: CurrentScale? = null

    /**
     * Whether [validatedScale] has received its first emission from DataStore yet.
     *
     * `validatedScale == null` is ambiguous on its own: it means either "DataStore has
     * genuinely never validated a scale on this install" (safe to fall through to
     * [CurrentScaleDetector.fromMagnitude]'s guess) or "a scale *was* measured and
     * persisted on a previous run, but this fresh `@Singleton`'s collector has not
     * received its first emission yet" (not safe -- a measured-and-persisted value
     * exists on disk right now). Without this flag, every `currentUa()`/`currentSample()`
     * call in that short cold-start window would answer with a guess while the earned
     * answer sits unread a few milliseconds away, and `SampleWriter` would persist that
     * guess into a row indistinguishable from a validated one. [scaleLoaded] lets
     * `currentUa()` tell the two cases apart and abstain (`Reading.Unsupported`) rather
     * than guess during the second one.
     */
    @Volatile private var scaleLoaded: Boolean = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settingsStore.currentScale.collect {
                validatedScale = it
                scaleLoaded = true
            }
        }
    }

    fun chargeCounterUah(): Reading<Long> = read(BatteryProperty.ChargeCounter) { it.toLong() }

    /**
     * True microamps, never the device's untouched register value. Android documents
     * CURRENT_NOW as microamps; some OEMs (the Galaxy A35 this was found on, among others)
     * report milliamps instead, so a raw passthrough here is exactly the defect this method
     * exists to not repeat.
     *
     * [isCharging] should be `broadcast.chargeState.isActivelyCharging` from the same
     * battery-changed broadcast a caller already has in hand -- see
     * [com.alaminahamed.batteryhealth.domain.isActivelyCharging] for why `Full` does not
     * count. It is threaded in here rather than mirrored from a broadcast this class
     * subscribes to itself, to avoid a second asynchronous collector with its own cold-start
     * race, on top of the one [scaleLoaded] already exists to close.
     *
     * Priority order, most to least trustworthy, and the general guess is never allowed to
     * override the specific measurement once one exists: a counter-validated [validatedScale]
     * wins unconditionally over the current reading's own magnitude, because it is a
     * measurement rather than a heuristic; failing that, [CurrentScaleDetector.fromMagnitude]
     * gives an immediate, charge-state-gated answer when the reading is unambiguous; failing
     * that, this returns Unsupported -- no invented data, even temporarily. See
     * [scaleLoaded] for the one further case this method abstains on: not yet knowing
     * whether a validated scale already exists on disk.
     *
     * The sentinel check below shares [BatteryProperty.isPlausibleReading] with
     * [CapabilityProbe] and [read] rather than re-deriving it: [Int.MIN_VALUE] is rejected
     * before any scale is even considered, let alone multiplied through, but that alone is
     * not the whole rule -- see [BatteryProperty.isPlausibleReading]'s doc for why folding
     * every property's sentinel into one check here would repeat the exact defect Task 4
     * fixed one layer up, in [CapabilityProbe].
     */
    fun currentUa(isCharging: Boolean): Reading<Int> {
        if (BatteryProperty.CurrentNow !in capabilities) return Reading.Unsupported
        val raw = batteryManager.getIntProperty(BatteryProperty.CurrentNow.id)
        return scaledCurrent(raw, isCharging).reading
    }

    /**
     * Both interpretations of one physical CURRENT_NOW reading, taken from a single
     * `getIntProperty` call -- unlike calling [currentUa] and a separate raw accessor back
     * to back, which would read this volatile hardware register twice for what is meant to
     * be one sample's two columns. Two reads risk the two columns describing different
     * instants of a value that can move between them, and doubles the register reads of a
     * five-second sampler for no benefit.
     *
     * [currentScaleValidated] mirrors, for this one reading, whether [currentUa] came from
     * a counter-confirmed [validatedScale] (`true`) or only [CurrentScaleDetector.fromMagnitude]'s
     * guess (`false`); `null` when [currentUa] itself is [Reading.Unsupported] and there is no
     * scale to attribute provenance to. `SampleWriter` persists this alongside the value so a
     * later aggregation can refuse to build a "Measured" figure out of a row this app never
     * actually earned -- see `SampleEntity.currentScaleValidated`'s own doc.
     */
    data class CurrentSample(
        val currentUa: Reading<Int>,
        val currentRawUnits: Reading<Int>,
        val currentScaleValidated: Boolean?,
    )

    fun currentSample(isCharging: Boolean): CurrentSample {
        if (BatteryProperty.CurrentNow !in capabilities) {
            return CurrentSample(Reading.Unsupported, Reading.Unsupported, currentScaleValidated = null)
        }
        val raw = batteryManager.getIntProperty(BatteryProperty.CurrentNow.id)
        val rawReading = if (BatteryProperty.CurrentNow.isPlausibleReading(raw)) {
            Reading.Available(raw, Source.Framework)
        } else {
            Reading.Unsupported
        }
        val (scaledReading, usedValidatedScale) = scaledCurrent(raw, isCharging)
        val validatedFlag = if (scaledReading is Reading.Available) usedValidatedScale else null
        return CurrentSample(scaledReading, rawReading, validatedFlag)
    }

    private data class ScaledCurrent(val reading: Reading<Int>, val usedValidatedScale: Boolean)

    /**
     * The one place [validatedScale] and [CurrentScaleDetector.fromMagnitude] are actually
     * applied to a raw reading; [currentUa] and [currentSample] both delegate here so the
     * priority order and the [scaleLoaded] abstain-window live in exactly one place.
     */
    private fun scaledCurrent(raw: Int, isCharging: Boolean): ScaledCurrent {
        if (!BatteryProperty.CurrentNow.isPlausibleReading(raw)) {
            return ScaledCurrent(Reading.Unsupported, usedValidatedScale = false)
        }
        // Not yet known whether a validated scale already exists on disk -- see
        // [scaleLoaded]'s own doc for why this must abstain rather than fall through to a
        // guess that could silently override a measured value this process just hasn't
        // read back yet.
        if (!scaleLoaded) return ScaledCurrent(Reading.Unsupported, usedValidatedScale = false)
        val validated = validatedScale
        val scale = validated ?: CurrentScaleDetector.fromMagnitude(raw, isCharging)
            ?: return ScaledCurrent(Reading.Unsupported, usedValidatedScale = false)
        val trueMicroamps = scale.toMicroamps(raw)
        // Guards against a corrupt or wildly implausible register value silently wrapping
        // into a different, plausible-looking Int when narrowed below -- realistic phone
        // currents (single-digit amps) never approach this bound at either scale.
        if (trueMicroamps !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            return ScaledCurrent(Reading.Unsupported, usedValidatedScale = false)
        }
        return ScaledCurrent(
            Reading.Available(trueMicroamps.toInt(), Source.Framework),
            usedValidatedScale = validated != null,
        )
    }

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
         * call as unconditional. That annotation is a promise to lint, not an enforced
         * contract on this function's parameter: it only holds at a call site that
         * actually passes `Build.VERSION.SDK_INT` (as [chargeTimeRemainingMs] does) --
         * calling this with an arbitrary `sdkInt` for some other purpose would still
         * type-check and still satisfy lint, without actually gating anything real.
         */
        @VisibleForTesting
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
        fun isChargeTimeRemainingSupported(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.P
    }
}
