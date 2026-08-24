package com.mralaminahamed.batteryhealth.data.privileged

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbGatewayTest {

    private class FakeShell(
        initial: TransportState = TransportState.Unavailable,
        private val dump: String? = null,
        private val checkin: String? = null,
    ) : PrivilegedShell {
        val flow = MutableStateFlow(initial)
        override val state = flow
        var connectCalls = 0
            private set

        override suspend fun runDump() = dump
        override suspend fun runCheckin() = checkin
        override suspend fun connect() { connectCalls++ }
        override fun refresh() = Unit
    }

    @Test
    fun routesDumpsToWhicheverTransportIsReady() = runTest {
        val adb = FakeShell(TransportState.Ready, dump = "level: 84\n")
        val gateway = AdbGateway(FakeShell(), adb)

        assertEquals(PrivilegedAvailability.Ready(Transport.Adb), gateway.state.value)
        assertEquals("level: 84\n", gateway.dumpBattery())
    }

    @Test
    fun prefersRootWhenBothAreReady() = runTest {
        val root = FakeShell(TransportState.Ready, dump = "from root")
        val adb = FakeShell(TransportState.Ready, dump = "from adb")

        assertEquals("from root", AdbGateway(root, adb).dumpBattery())
    }

    @Test
    fun returnsNullWhenNoTransportIsReady() = runTest {
        val gateway = AdbGateway(FakeShell(), FakeShell())

        assertNull(gateway.dumpBattery())
        assertNull(gateway.dumpBatteryStatsCheckin())
    }

    @Test
    fun degradesLiveWhenAReadyTransportDrops() = runTest {
        // The property the Shizuku gateway had and must not lose: a transport dying
        // mid-session reaches state with no exception anywhere downstream.
        val adb = FakeShell(TransportState.Ready, dump = "level: 84\n")
        val gateway = AdbGateway(FakeShell(), adb)
        assertEquals(PrivilegedAvailability.Ready(Transport.Adb), gateway.state.value)

        adb.flow.value = TransportState.Unavailable

        // Not a synchronous assertion on gateway.state.value: the gateway's own combine
        // collects on a real Dispatchers.Default thread (see AdbGateway.scope's own doc
        // for why it has to be a real dispatcher, not Unconfined), so the mutation above
        // and this state reflecting it are two genuinely separate events with no ordering
        // guarantee against the very next line of test code. Awaiting the expected value
        // asserts the same property -- the drop reaches state -- without depending on
        // scheduling; a real wall-clock bound (not virtual time) is required here because
        // the wait is for an external, real-dispatcher event, not a `delay`.
        withTimeout(2_000) { gateway.state.first { it == PrivilegedAvailability.Unavailable } }

        assertNull(gateway.dumpBattery())
    }

    @Test
    fun neverProbesRootBeforeConnectIsCalled() = runTest {
        val root = FakeShell()
        AdbGateway(root, FakeShell())

        // Constructing the gateway must not raise Magisk's dialog.
        assertEquals(0, root.connectCalls)
    }
}
