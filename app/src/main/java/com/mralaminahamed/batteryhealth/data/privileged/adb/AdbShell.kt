package com.mralaminahamed.batteryhealth.data.privileged.adb

import com.mralaminahamed.batteryhealth.data.privileged.CMD_DUMP_BATTERY
import com.mralaminahamed.batteryhealth.data.privileged.CMD_DUMP_CHECKIN
import com.mralaminahamed.batteryhealth.data.privileged.PrivilegedShell
import com.mralaminahamed.batteryhealth.data.privileged.TransportState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounds the ADB auth handshake itself, not [runDump]/[runCheckin] -- [AdbConnection]'s own
 * `soTimeoutMs` is a single per-read bound shared by every read on the connection, so it has
 * to be sized for the handshake (host round trip plus, on the very first pairing, a
 * human tapping "Allow") rather than either dump's own budget. The two dump-specific
 * budgets below are enforced separately, per call, with [withTimeoutOrNull].
 */
private const val HANDSHAKE_TIMEOUT_MS = 5_000

/** Same value and the same rationale as [PrivilegedBatteryService]'s `DUMP_TIMEOUT_SECONDS`:
 * `dumpsys battery`'s own output is small and fast, so a generous-but-bounded budget here
 * catches a wedged shell without making a normal call wait noticeably longer than it needs. */
private const val DUMP_TIMEOUT_MS = 3_000L

/** Same value and the same rationale as [PrivilegedBatteryService]'s
 * `CHECKIN_TIMEOUT_SECONDS`: `dumpsys batterystats --checkin` runs roughly 50x larger than
 * the plain battery dump, so it gets proportionally more room before this gives up on it. */
private const val CHECKIN_TIMEOUT_MS = 8_000L

/**
 * The ADB transport: one [AdbConnection] dialed against the loopback address and an
 * injected port, reused across every [runDump]/[runCheckin] call rather than opened fresh
 * per command -- the auth handshake is the expensive, user-paced part, and there is no
 * reason to repeat it for every `dumpsys` call the way there would be no reason to redial a
 * TCP connection per HTTP request on a keep-alive client.
 *
 * A plain constructor, not `@Inject`: the port this needs to dial lives in a settings
 * store that does not exist yet at this point in the build-out, so an injected constructor
 * would not compile. The gateway that wires this up from `SettingsStore.adbPort` and
 * `AdbKeyPair.loadOrCreate()` is a later task's job, not this class's.
 */
class AdbShell(
    private val port: Int,
    private val signer: AdbSigner,
) : PrivilegedShell {

    // No natural external owner to launch refresh()'s reconnect on: refresh() is a plain,
    // non-suspend interface method (called synchronously from ON_RESUME), so the suspend
    // work it triggers needs somewhere to run. Mirrors ShizukuGateway's own self-owned
    // scope for the same "nothing outside this class can cancel it for us" reason.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TransportState>(TransportState.Unavailable)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    @Volatile private var connection: AdbConnection? = null

    // Mirrors ShizukuGateway's bindInFlight: without this, every ON_RESUME's refresh()
    // could overlap a connect() already in progress and each issue its own socket, only
    // one of which ever gets stored in `connection` -- the other silently orphaned.
    private val connectInFlight = AtomicBoolean(false)

    override suspend fun connect() {
        if (!connectInFlight.compareAndSet(false, true)) return
        try {
            // Close the previous socket before opening the next one. refresh() reconnects
            // after every transport failure and the Health screen calls refresh() on every
            // ON_RESUME -- skip this line and every single resume leaks a socket for the
            // rest of the process's life. Do not "simplify" this away.
            connection?.close()
            val next = AdbConnection(port = port, signer = signer, soTimeoutMs = HANDSHAKE_TIMEOUT_MS)
            connection = next
            _state.value = next.connect().toTransportState()
        } finally {
            connectInFlight.set(false)
        }
    }

    override fun refresh() {
        scope.launch { connect() }
    }

    override suspend fun runDump(): String? = run(CMD_DUMP_BATTERY, DUMP_TIMEOUT_MS)

    override suspend fun runCheckin(): String? = run(CMD_DUMP_CHECKIN, CHECKIN_TIMEOUT_MS)

    private suspend fun run(command: String, timeoutMs: Long): String? {
        // Gated on Ready rather than merely "connection != null": a connection whose own
        // connect() ended in AwaitingAuthorization/Unavailable/Failed still exists as an
        // object (its socket may never have opened) and calling shell() on it would either
        // throw or hang rather than fail cleanly the way this method's null contract promises.
        val current = connection?.takeIf { _state.value == TransportState.Ready } ?: return null
        // withTimeoutOrNull's cancellation clock comes from whatever dispatcher is current
        // *at the point it is called* -- called from the test dispatcher directly, its
        // delay would run on kotlinx-coroutines-test's virtual clock, which free-runs the
        // instant nothing else is scheduled on it, firing this "timeout" instantly even
        // though the real socket exchange (on Dispatchers.IO, a real dispatcher outside the
        // test scheduler's view) hasn't finished. Entering Dispatchers.IO first anchors the
        // timeout to that dispatcher's real-time clock instead, matching the real budget
        // this method promises in production.
        val result = withContext(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { current.shell(command) } }
        if (result == null) {
            // Live degradation: a transport that was Ready and just failed must reach
            // `state` with no exception anywhere downstream, so every privileged reading
            // degrades on the repository's next emission rather than on a crash.
            _state.value = TransportState.Unavailable
        }
        return result
    }

    /**
     * Deliberately not on [PrivilegedShell]: production's gateway holds this transport for
     * the life of the process (same design as [ShizukuGateway]), so no production caller
     * ever needs to close it, and putting this on the interface would invite closing a
     * transport that should outlive its callers. Tests need it anyway, to avoid leaking the
     * client-side socket when the fake daemon they talk to is torn down.
     */
    fun close() {
        scope.cancel()
        connection?.close()
    }

    private fun AdbConnectResult.toTransportState(): TransportState = when (this) {
        AdbConnectResult.Connected -> TransportState.Ready
        AdbConnectResult.AwaitingAuthorization -> TransportState.AwaitingAuthorization
        AdbConnectResult.Unreachable -> TransportState.Unavailable
        AdbConnectResult.Failed -> TransportState.Unavailable
    }
}
