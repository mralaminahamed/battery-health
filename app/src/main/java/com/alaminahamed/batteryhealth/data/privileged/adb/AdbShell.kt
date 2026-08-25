package com.alaminahamed.batteryhealth.data.privileged.adb

import com.alaminahamed.batteryhealth.data.privileged.CMD_DUMP_BATTERY
import com.alaminahamed.batteryhealth.data.privileged.CMD_DUMP_CHECKIN
import com.alaminahamed.batteryhealth.data.privileged.PrivilegedShell
import com.alaminahamed.batteryhealth.data.privileged.TransportState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * This connection's *default* per-read socket timeout -- set once by [AdbConnection.connect]
 * and otherwise in force for every read on it, including [runDump]/[runCheckin]'s, unless
 * one of them narrows it first with [AdbConnection.withSoTimeout]. It has to be sized for
 * the handshake: a real round trip to the device, plus, on first pairing, a human noticing
 * and tapping "Allow USB debugging". Matches [RootShell]'s `PROBE_TIMEOUT_SECONDS` (10s) on
 * purpose -- both constants bound the same kind of wait, a person answering an on-device
 * authorization dialog, and there is no reason for ADB's to be shorter than root's.
 */
private const val HANDSHAKE_TIMEOUT_MS = 10_000

/** `dumpsys battery`'s own output is small and fast, so a generous-but-bounded budget here
 * catches a wedged shell without making a normal call wait noticeably longer than it needs
 * -- same value and rationale as `RootShell`'s own `ROOT_DUMP_TIMEOUT_SECONDS`. Enforced by
 * narrowing the socket's own read timeout for the duration of the call -- see
 * [AdbConnection.withSoTimeout]'s doc for why a coroutine-level timeout cannot do this. */
private const val DUMP_TIMEOUT_MS = 3_000

/** `dumpsys batterystats --checkin` runs roughly 50x larger than the plain battery dump,
 * so it gets proportionally more room before this gives up on it -- same value and
 * rationale as `RootShell`'s own `ROOT_CHECKIN_TIMEOUT_SECONDS`. */
private const val CHECKIN_TIMEOUT_MS = 8_000

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
 *
 * [portProvider] is a suspend supplier, not a plain `Int`: [connect] calls it fresh on
 * every reconnect rather than a value captured once at construction time. `setAdbPort` has
 * no production caller yet, but the moment a settings screen calls it, a captured `Int`
 * would keep dialing the old port until the process happened to die -- silently, since
 * `refresh()`'s reconnect would still "succeed" against whatever was listening on the
 * stale port. Reading straight from `SettingsStore.adbPort` each time is what makes a port
 * change actually take effect on the very next connect.
 */
class AdbShell(
    private val portProvider: suspend () -> Int,
    private val signer: AdbSigner,
) : PrivilegedShell {

    // No natural external owner to launch refresh()'s reconnect on: refresh() is a plain,
    // non-suspend interface method (called synchronously from ON_RESUME), so the suspend
    // work it triggers needs somewhere to run. Mirrors AdbGateway's own self-owned scope
    // for the same "nothing outside this class can cancel it for us" reason.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TransportState>(TransportState.Unavailable)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    @Volatile private var connection: AdbConnection? = null

    // Latched, not reset: once close() has been asked for, no connect() started before or
    // after that request is allowed to leave a live connection behind. See close()'s own
    // doc and the recheck at the end of connect() below for how this actually closes the
    // race that a bare `connectInFlight` guard cannot -- connect() being "not concurrently
    // running twice" says nothing about a close() landing while the one call that IS running
    // is still mid-handshake.
    @Volatile private var closed = false

    // Mirrors RootShell's own connectInFlight: without this, every ON_RESUME's refresh()
    // could overlap a connect() already in progress and each issue its own socket, only
    // one of which ever gets stored in `connection` -- the other silently orphaned.
    private val connectInFlight = AtomicBoolean(false)

    // This transport is deliberately one command at a time: a single AdbConnection, reused
    // across calls, has no stream-id filtering strong enough on its own to make two
    // concurrent shell() calls safe -- see AdbConnection.shell's own doc. Two coroutines
    // both sitting in shell() on this one unsynchronized socket is not theoretical:
    // BatteryRepository's snapshots and appPower flows are two independent collectors that
    // both resolve to this one AdbShell, and a ready-transition or redump fires both
    // runDump()/runCheckin() together. This Mutex is what actually serializes them --
    // without it, whichever coroutine's read() happens to run next can consume the other
    // call's header, misattributing one dump's bytes to the other's caller.
    private val shellMutex = Mutex()

    override suspend fun connect() {
        if (!connectInFlight.compareAndSet(false, true)) return
        try {
            if (closed) return
            // Close the previous socket before opening the next one. refresh() reconnects
            // after every transport failure and the Health screen calls refresh() on every
            // ON_RESUME -- skip this line and every single resume leaks a socket for the
            // rest of the process's life. Do not "simplify" this away.
            connection?.close()
            val next = AdbConnection(port = portProvider(), signer = signer, soTimeoutMs = HANDSHAKE_TIMEOUT_MS)
            connection = next
            val result = next.connect()
            if (closed) {
                // close() landed while the handshake above was in flight. `next` may have
                // just succeeded -- the socket is real and open -- but nothing else will
                // ever close it once this method returns, so this is the one chance to.
                next.close()
                connection = null
                return
            }
            _state.value = result.toTransportState()
        } finally {
            connectInFlight.set(false)
        }
    }

    override fun refresh() {
        scope.launch { connect() }
    }

    override suspend fun runDump(): String? = run(CMD_DUMP_BATTERY, DUMP_TIMEOUT_MS)

    override suspend fun runCheckin(): String? = run(CMD_DUMP_CHECKIN, CHECKIN_TIMEOUT_MS)

    private suspend fun run(command: String, timeoutMs: Int): String? = shellMutex.withLock {
        // Gated on Ready rather than merely "connection != null": a connection whose own
        // connect() ended in AwaitingAuthorization/Unavailable/Failed still exists as an
        // object (its socket may never have opened) and calling shell() on it would either
        // throw or hang rather than fail cleanly the way this method's null contract promises.
        val current = connection?.takeIf { _state.value == TransportState.Ready } ?: return@withLock null
        // The real bound is the socket's own read timeout, narrowed for just this call --
        // see AdbConnection.withSoTimeout's doc for why a coroutine-level timeout cannot
        // preempt the blocking reads inside shell().
        val result = current.withSoTimeout(timeoutMs) { current.shell(command) }
        if (result == null) {
            // Live degradation: a transport that was Ready and just failed must reach
            // `state` with no exception anywhere downstream, so every privileged reading
            // degrades on the repository's next emission rather than on a crash.
            _state.value = TransportState.Unavailable
        }
        result
    }

    /**
     * Deliberately not on [PrivilegedShell]: production's gateway holds this transport for
     * the life of the process (same design as [AdbGateway]), so no production caller
     * ever needs to close it, and putting this on the interface would invite closing a
     * transport that should outlive its callers. Tests need it anyway, to avoid leaking the
     * client-side socket when the fake daemon they talk to is torn down.
     *
     * Latches [closed] before touching [connection]: a `refresh()` launched moments earlier
     * may still be mid-handshake on [scope] when this runs, and closing only whatever
     * [connection] happens to hold *right now* would miss a connection that finishes and
     * gets assigned a moment later. The recheck at the end of [connect] is what actually
     * catches that case; this method's own close is what handles every other one.
     */
    fun close() {
        closed = true
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
