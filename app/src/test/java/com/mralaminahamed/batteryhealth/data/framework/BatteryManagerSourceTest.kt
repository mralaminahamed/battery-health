package com.mralaminahamed.batteryhealth.data.framework

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BatteryManagerSource` itself needs a real `android.os.BatteryManager` and cannot be
 * constructed on the JVM (no Robolectric here), but the API-28 gate in
 * `chargeTimeRemainingMs()` is extracted as a pure predicate over `Build.VERSION.SDK_INT`
 * specifically so the minSdk-26 boundary is provable without a real API 26/27 device.
 */
class BatteryManagerSourceTest {

    @Test
    fun api26And27AreBelowTheComputeChargeTimeRemainingFloor() {
        assertFalse(BatteryManagerSource.isChargeTimeRemainingSupported(sdkInt = 26))
        assertFalse(BatteryManagerSource.isChargeTimeRemainingSupported(sdkInt = 27))
    }

    @Test
    fun api28AndAboveCanCallComputeChargeTimeRemaining() {
        assertTrue(BatteryManagerSource.isChargeTimeRemainingSupported(sdkInt = 28))
        // 36: the real Galaxy A35 this app was verified against.
        assertTrue(BatteryManagerSource.isChargeTimeRemainingSupported(sdkInt = 36))
    }
}
