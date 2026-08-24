package com.mralaminahamed.batteryhealth.data.privileged.adb

import com.mralaminahamed.batteryhealth.data.privileged.CMD_DUMP_BATTERY
import com.mralaminahamed.batteryhealth.data.privileged.CMD_DUMP_CHECKIN
import com.mralaminahamed.batteryhealth.data.privileged.TransportState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbShellTest {

    private var daemon: FakeAdbDaemon? = null

    // close() is on the concrete class, not PrivilegedShell -- see AdbShell's own doc for
    // why. Without this, every test here leaks the client-side socket even after the fake
    // daemon it talked to is torn down; matching how AdbConnectionTest/AdbStreamTest close
    // the AdbConnection they construct directly.
    private var adbShell: AdbShell? = null

    @After
    fun tearDown() {
        adbShell?.close()
        daemon?.stop()
    }

    @Test
    fun runsBothAllowlistedCommands() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(
            knownPublicKeyLine = line,
            shellResponses = mapOf(
                "shell:$CMD_DUMP_BATTERY" to "level: 84\n".toByteArray(),
                "shell:$CMD_DUMP_CHECKIN" to "9,0,i,vers,36\n".toByteArray(),
            ),
        ).also { daemon = it; it.start() }

        val shell = AdbShell(port = fake.port, signer = signer).also { adbShell = it }
        shell.connect()

        assertEquals(TransportState.Ready, shell.state.value)
        assertEquals("level: 84\n", shell.runDump())
        assertEquals("9,0,i,vers,36\n", shell.runCheckin())
    }

    @Test
    fun isUnavailableAndReturnsNullWhenNothingIsListening() = runTest {
        val closed = FakeAdbDaemon().also { it.start() }
        val port = closed.port
        closed.stop()

        val shell = AdbShell(port = port, signer = FakeAdbDaemon.signer().first).also { adbShell = it }
        shell.connect()

        assertEquals(TransportState.Unavailable, shell.state.value)
        assertNull(shell.runDump())
    }
}
