package com.alaminahamed.batteryhealth.data.privileged

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
        var refreshCalls = 0
            private set

        override suspend fun runDump() = dump
        override suspend fun runCheckin() = checkin
        override suspend fun connect() { connectCalls++ }
        override fun refresh() { refreshCalls++ }
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

    // connect() itself -- as opposed to construction, which the test above covers -- was
    // previously unexercised entirely: deleting `if (shouldConnectRoot())`, inverting it,
    // or dropping `adb.connect()` from AdbGateway.connect() all left every test green. The
    // three tests below each pin one edge of that gate.

    @Test
    fun connectAlwaysDialsAdbRegardlessOfShouldConnectRoot() = runTest {
        val adb = FakeShell()
        val gateway = AdbGateway(FakeShell(), adb, shouldConnectRoot = { false })

        gateway.connect()

        // adb has no on-device dialog of its own to raise unprompted, so it is dialed
        // unconditionally -- see AdbGateway.connect()'s own doc.
        assertEquals(1, adb.connectCalls)
    }

    @Test
    fun connectProbesRootWhenShouldConnectRootReturnsTrue() = runTest {
        val root = FakeShell()
        val gateway = AdbGateway(root, FakeShell(), shouldConnectRoot = { true })

        gateway.connect()

        assertEquals(1, root.connectCalls)
    }

    @Test
    fun connectNeverProbesRootWhenShouldConnectRootReturnsFalse() = runTest {
        val root = FakeShell()
        val gateway = AdbGateway(root, FakeShell(), shouldConnectRoot = { false })

        gateway.connect()

        // Running `su` at all is what raises Magisk's own grant dialog, so this must stay
        // gated -- an unconditional root.connect() here would pop that dialog unprompted.
        assertEquals(0, root.connectCalls)
    }

    /**
     * `refresh()` runs from every `ON_RESUME`, unprompted, so it must not dial a transport
     * the user never opted into.
     *
     * adb used to be refreshed unconditionally, on the stated reasoning that it "has no
     * on-device dialog of its own to raise unprompted". That is false: where `adb tcpip` is
     * enabled, connecting to loopback with a key adbd does not recognise raises Android's
     * "Allow USB debugging?" prompt. On real hardware, a fresh install launched once and
     * touched no further put that dialog in front of a user who had never asked for the
     * privileged tier.
     */
    @Test
    fun refreshDialsNeitherTransportTheUserNeverOptedInto() = runTest {
        val root = FakeShell()
        val adb = FakeShell()
        val gateway = AdbGateway(root, adb)

        gateway.refresh()
        advanceUntilIdle()

        assertEquals(0, root.refreshCalls)
        assertEquals(0, adb.refreshCalls)
    }

    @Test
    fun refreshReconnectsOnlyTheTransportsAlreadyGranted() = runTest {
        val root = FakeShell()
        val adb = FakeShell()
        val gateway = AdbGateway(
            root = root,
            adb = adb,
            shouldConnectRoot = { false },
            shouldReconnectAdb = { true },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        gateway.refresh()
        advanceUntilIdle()

        assertEquals("adb was granted, so it reconnects", 1, adb.refreshCalls)
        assertEquals("root was not, so it must not be dialled", 0, root.refreshCalls)
    }

    /**
     * The flag is earned by a successful connection, never by an attempt. A refused or
     * failed connect must not entitle the app to keep retrying unprompted afterwards.
     */
    @Test
    fun onlyASuccessfulConnectionEarnsTheRightToReconnectLater() = runTest {
        var recorded = false
        // Stays Unavailable through connect(), which is exactly a refused or failed attempt.
        val adb = FakeShell(TransportState.Unavailable)
        AdbGateway(
            root = FakeShell(),
            adb = adb,
            recordAdbConnected = { recorded = true },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        ).connect()

        assertEquals(false, recorded)
    }

    @Test
    fun aSuccessfulConnectionIsRecordedSoLaterResumesMayReconnect() = runTest {
        var recorded = false
        AdbGateway(
            root = FakeShell(),
            adb = FakeShell(TransportState.Ready),
            recordAdbConnected = { recorded = true },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        ).connect()

        assertEquals(true, recorded)
    }
}
