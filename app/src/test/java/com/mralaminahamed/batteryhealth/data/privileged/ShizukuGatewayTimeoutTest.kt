package com.mralaminahamed.batteryhealth.data.privileged

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [withGatewayDumpTimeout] is [ShizukuGateway.dumpBattery]'s Important-2 fix pulled out to
 * a top-level function precisely so it is reachable without Shizuku's real static
 * singleton, which [ShizukuGateway] itself needs just to construct. `runTest`'s virtual
 * clock lets the "too slow" case below assert the timeout actually fires without this
 * test suite really waiting out the delay in wall-clock time.
 */
class ShizukuGatewayTimeoutTest {

    @Test
    fun aFastCallReturnsNormally() = runTest {
        val result = withGatewayDumpTimeout { "dump text" }

        assertEquals("dump text", result)
    }

    /**
     * RED without the `withTimeoutOrNull` wrapper (a plain `block()` call instead): this
     * would hang the real `combine` in `BatteryRepository.snapshots()` forever on a wedged
     * Binder transaction, exactly as Important 2 describes -- reproduced here as `delay`
     * past [GATEWAY_DUMP_TIMEOUT_MS] rather than an actual wedged Binder call, which is not
     * reachable from a JVM test. GREEN with it: `null`, not the delayed value, and this
     * test itself completes instantly under `runTest`'s virtual time rather than actually
     * waiting the delay out.
     */
    @Test
    fun aCallSlowerThanTheTimeoutReturnsNullRatherThanHanging() = runTest {
        val result = withGatewayDumpTimeout {
            delay(GATEWAY_DUMP_TIMEOUT_MS + 1_000L)
            "too-late"
        }

        assertNull(result)
    }
}
