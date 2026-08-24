package com.mralaminahamed.batteryhealth.data.privileged

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun degradesLiveWhenAReadyTransportDrops() = runTest {
        // The property the previous (now-deleted) privileged gateway had and must not
        // lose: a transport dying mid-session reaches state with no exception anywhere
        // downstream.
        //
        // UnconfinedTestDispatcher(), not the production Dispatchers.Default this
        // gateway defaults to: it collapses the combine-and-collect coroutine onto this
        // test's own thread, so the mutation below and state reflecting it happen
        // synchronously, in order, with no dispatch gap between them. That makes the
        // direct assertEquals below sound -- it would previously have raced production's
        // real dispatcher, which is exactly why this test used to await propagation with
        // `state.first { ... }` under a wall-clock timeout instead.
        val adb = FakeShell(TransportState.Ready, dump = "level: 84\n")
        val gateway = AdbGateway(FakeShell(), adb, dispatcher = UnconfinedTestDispatcher())
        assertEquals(PrivilegedAvailability.Ready(Transport.Adb), gateway.state.value)

        adb.flow.value = TransportState.Unavailable

        assertEquals(PrivilegedAvailability.Unavailable, gateway.state.value)
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
