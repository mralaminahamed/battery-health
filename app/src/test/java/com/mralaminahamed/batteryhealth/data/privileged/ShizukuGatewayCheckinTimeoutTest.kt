package com.mralaminahamed.batteryhealth.data.privileged

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [withGatewayCheckinTimeout]'s own equivalent of [ShizukuGatewayTimeoutTest] --
 * [ShizukuGateway.dumpBatteryStatsCheckin]'s Binder-call bound pulled out to a top-level
 * function for the same reason [withGatewayDumpTimeout] is, and given its own test class
 * rather than folded into [ShizukuGatewayTimeoutTest] because it is a genuinely separate
 * function guarding a separate constant ([GATEWAY_CHECKIN_TIMEOUT_MS], not
 * [GATEWAY_DUMP_TIMEOUT_MS]) for a call with its own, independently-justified headroom --
 * see that constant's own doc.
 */
class ShizukuGatewayCheckinTimeoutTest {

    @Test
    fun aFastCallReturnsNormally() = runTest {
        val result = withGatewayCheckinTimeout { "checkin text" }

        assertEquals("checkin text", result)
    }

    /**
     * Same shape as [ShizukuGatewayTimeoutTest.aCallSlowerThanTheTimeoutReturnsNullRatherThanHanging]:
     * `runTest`'s virtual clock lets this assert the timeout actually fires without
     * really waiting [GATEWAY_CHECKIN_TIMEOUT_MS] out.
     */
    @Test
    fun aCallSlowerThanTheTimeoutReturnsNullRatherThanHanging() = runTest {
        val result = withGatewayCheckinTimeout {
            delay(GATEWAY_CHECKIN_TIMEOUT_MS + 1_000L)
            "too-late"
        }

        assertNull(result)
    }
}
