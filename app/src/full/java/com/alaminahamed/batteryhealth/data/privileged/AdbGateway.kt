package com.alaminahamed.batteryhealth.data.privileged

import com.alaminahamed.batteryhealth.data.privileged.adb.AdbKeyPair
import com.alaminahamed.batteryhealth.data.privileged.adb.AdbShell
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The concrete [PrivilegedBatterySource] this app ships: two transports, [RootShell] and
 * [AdbShell] (both [PrivilegedShell]s), fronted by one live [state] rather than a single
 * static singleton. Everything above this class (the repository, the ViewModels, the
 * Health screen) depends only on [PrivilegedBatterySource]; this class is the only thing
 * that knows two transports exist at all.
 *
 * The primary constructor takes two [PrivilegedShell]s directly -- not [RootShell] and
 * [AdbShell] by name -- purely so [AdbGatewayTest] can drive it with plain fakes; the
 * [Inject]-annotated constructor below is what production actually wires up, delegating
 * straight through with the real transports and [SettingsStore] in hand.
 */
@Singleton
class AdbGateway(
    private val root: PrivilegedShell,
    private val adb: PrivilegedShell,
    // A suspend supplier, not a plain Boolean or a SettingsStore reference: it is what lets
    // the two-PrivilegedShell constructor above stay exactly that -- two shells, nothing
    // else required to construct one for a test -- while still giving connect() a real
    // answer to "should root be probed" in production. Defaults to "never": a gateway built
    // without one (as every test above does) must never probe root on its own, matching
    // connect()'s own never-probe-unprompted contract.
    private val shouldConnectRoot: suspend () -> Boolean = { false },
    // Same shape and same purpose as shouldConnectRoot, for the transport that turned out
    // to need it just as much. Defaults to "never": a gateway built without one must not
    // dial adb on its own.
    private val shouldReconnectAdb: suspend () -> Boolean = { false },
    // Called once adb has actually reached Ready, so a later ON_RESUME may reconnect it
    // without asking again. Recording only on success is the point -- a failed or refused
    // attempt must not earn the app permission to keep retrying unprompted.
    private val recordAdbConnected: suspend () -> Unit = {},
    // See scope's own doc below for why this defaults to Dispatchers.Default and why it
    // is a constructor parameter at all rather than that default hardcoded inline.
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PrivilegedBatterySource {

    @Inject constructor(
        rootShell: RootShell,
        adbShell: AdbShell,
        settingsStore: SettingsStore,
    ) : this(
        root = rootShell,
        adb = adbShell,
        shouldConnectRoot = { settingsStore.rootPreviouslyGranted.first() },
        shouldReconnectAdb = { settingsStore.adbPreviouslyConnected.first() },
        recordAdbConnected = { settingsStore.setAdbPreviouslyConnected(true) },
    )

    // No natural owner to cancel this for: like the gateway this replaced, this is a
    // @Singleton meant to live exactly as long as the process does.
    //
    // dispatcher is a constructor parameter, not Dispatchers.Default hardcoded inline,
    // purely so AdbGatewayTest can substitute UnconfinedTestDispatcher() and assert
    // gateway.state.value directly, with no wait for propagation. The Inject constructor
    // above never passes one, so production always gets the default -- and that default
    // has to be Dispatchers.Default, not Dispatchers.Unconfined: an earlier version of
    // this class used Unconfined, reasoning that state's combine-and-collect transform is
    // pure and cheap enough to run inline on whichever thread emits. That was the wrong
    // fix for the problem it was solving. Unconfined resumes a coroutine on whatever
    // thread triggered it -- here, whatever thread last wrote root._state.value or
    // adb._state.value, which for AdbShell/RootShell can be a socket-read or process-exec
    // thread. Running this gateway's own reducer on borrowed I/O threads, with the
    // execution context varying by caller, is exactly the kind of implicit coupling that
    // looks harmless in review and produces confusing behaviour later; Default keeps this
    // collector's execution context fixed and predictable regardless of which transport
    // changed -- in production, where nothing overrides the default.
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override val state: StateFlow<PrivilegedAvailability> = combine(
        root.state,
        adb.state,
    ) { r, a -> privilegedAvailability(r, a) }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = privilegedAvailability(root.state.value, adb.state.value),
    )

    override suspend fun dumpBattery(): String? = withGatewayDumpTimeout {
        transportFor(state.value)?.runDump()
    }

    override suspend fun dumpBatteryStatsCheckin(): String? = withGatewayCheckinTimeout {
        transportFor(state.value)?.runCheckin()
    }

    /**
     * adb is dialed unconditionally -- it has no on-device dialog of its own to raise
     * unprompted, only the wireless-pairing prompt a user has to have already started
     * pairing to see. root is different: running `su` at all is what raises Magisk's own
     * "Grant root access?" dialog, so it is gated on [shouldConnectRoot] -- true only when
     * a previous session already recorded a grant, or a caller (a future settings toggle)
     * set that persisted flag moments before calling this. There is no separate
     * "requestRoot" parameter on this method for that second case: flipping the flag first
     * is the one way any caller, past or present, opts into root's dialog, which keeps this
     * the single place that ever decides whether to run `su` -- never called from init, see
     * [RootShell.connect]'s own doc for why an eager call here would be exactly the
     * unprompted permission grab this app's design avoids elsewhere.
     */
    override suspend fun connect() {
        adb.connect()
        // Recorded only on success, and only here: this method is reached from the unlock
        // card's button, so arriving at Ready means the user asked for this tier and got
        // it. That is what later entitles refresh() to reconnect without asking again.
        if (adb.state.value == TransportState.Ready) {
            recordAdbConnected()
        }
        if (shouldConnectRoot()) {
            root.connect()
        }
    }

    /**
     * Reconnects transports the user has already opted into. No hot reconnect loop and no
     * scheduled retry -- see this class's own doc for why a background retry loop is the
     * one failure mode a battery-health app's design has to guard against hardest.
     * Retrying is the Health screen's job, on every `ON_RESUME`.
     *
     * Both transports are gated, and adb was not always. The reasoning for dialling it
     * unconditionally was that it "has no on-device dialog of its own to raise
     * unprompted", and that is false: on a device where `adb tcpip` is enabled, connecting
     * to loopback with a key adbd does not recognise raises Android's own "Allow USB
     * debugging?" prompt. Observed on real hardware -- a fresh install, launched once,
     * with no interaction at all, put that dialog in front of a user who had never asked
     * for the privileged tier.
     *
     * So an unprompted reconnect now requires a previous successful connection. A user who
     * never opts in is never dialled for, which is what "the privileged tier is optional"
     * has to mean in practice.
     */
    override fun refresh() {
        scope.launch {
            if (shouldConnectRoot()) root.refresh()
            if (shouldReconnectAdb()) adb.refresh()
        }
    }

    private fun transportFor(availability: PrivilegedAvailability): PrivilegedShell? =
        when ((availability as? PrivilegedAvailability.Ready)?.via) {
            Transport.Root -> root
            Transport.Adb -> adb
            null -> null
        }
}

/**
 * Comfortably above the transport-side bound each [PrivilegedShell] enforces on its own
 * read (3s for [AdbShell]'s `dumpsys battery`, 3s for [RootShell]'s) rather than equal to
 * it: a transport hitting its own bound already resolves to `null` well inside this
 * window, so this outer one's actual job is to catch what a transport-side bound cannot --
 * [state] itself never updating, or [transportFor] handing back a shell whose own read
 * call somehow never returns.
 *
 * **This is a best-effort outer bound, not a guarantee.** [withTimeoutOrNull] is
 * cooperative cancellation: it can only interrupt code that itself suspends and checks for
 * cancellation, and Task 7 already proved that pattern does not bound blocking I/O --
 * [com.alaminahamed.batteryhealth.data.privileged.adb.AdbConnection.send]/`read` are
 * blocking socket calls on a plain thread, and a coroutine cancellation signal cannot
 * preempt a thread parked inside one. If [PrivilegedShell.runDump] is ever blocked inside
 * such a call, this function's `withTimeoutOrNull` will not return early; it will wait
 * however long that blocking call takes. The real enforcement for [AdbShell] is the
 * per-read socket timeout it applies via `AdbConnection.withSoTimeout` around the same
 * call this wraps -- see that function's own doc. Do not read this constant as a hard
 * ceiling on how long [AdbGateway.dumpBattery] can take; it is not one.
 *
 * A top-level function, not inlined into [AdbGateway.dumpBattery] directly, purely so a
 * JVM test can exercise the timeout against a fake slow `block` under
 * `kotlinx-coroutines-test`'s virtual clock -- [AdbGateway] itself needs real
 * [PrivilegedShell]s to construct meaningfully and is not the seam this needs.
 */
internal const val GATEWAY_DUMP_TIMEOUT_MS = 7_000L

/**
 * [AdbGateway.dumpBattery]'s outer bound. **Best-effort, not a guarantee**: `block` calls
 * into a transport's blocking socket I/O, which this `withTimeoutOrNull` cannot preempt --
 * see [GATEWAY_DUMP_TIMEOUT_MS]'s own doc for the full reasoning. The transport's own
 * per-read socket timeout is the real mechanism; this is only the outer safety net around
 * whatever that timeout cannot itself catch.
 */
internal suspend fun <T> withGatewayDumpTimeout(block: suspend () -> T): T? =
    withTimeoutOrNull(GATEWAY_DUMP_TIMEOUT_MS) { block() }

/**
 * [GATEWAY_CHECKIN_TIMEOUT_MS]'s own equivalent of [GATEWAY_DUMP_TIMEOUT_MS] over
 * [AdbGateway.dumpBatteryStatsCheckin] rather than [AdbGateway.dumpBattery]: extra headroom
 * this call specifically needs and [GATEWAY_DUMP_TIMEOUT_MS] does not, because marshalling
 * a `String` up to 525KB back out of a transport is real, non-instant work the small ~11KB
 * dump's own gateway timeout was never sized for. A dedicated constant, not
 * [GATEWAY_DUMP_TIMEOUT_MS] reused: the two transport-side timeouts below them already
 * differ for reasons specific to each payload's size, and a shared client-side bound would
 * either be too tight for this call or too loose for the other.
 *
 * **The same best-effort caveat as [GATEWAY_DUMP_TIMEOUT_MS] applies here, doubly so**: a
 * 525KB read spends proportionally longer blocked inside the transport's own socket read
 * than the small dump's ~11KB does, which is exactly the code this `withTimeoutOrNull`
 * cannot preempt. The real bound is [AdbShell]'s own per-read socket timeout on this call;
 * this constant is an outer safety net for everything that timeout cannot catch (the
 * transport wedging somewhere other than the socket read itself), not a substitute for it.
 */
internal const val GATEWAY_CHECKIN_TIMEOUT_MS = 15_000L

/**
 * [AdbGateway.dumpBatteryStatsCheckin]'s outer bound. **Best-effort, not a guarantee**,
 * for the identical reason [withGatewayDumpTimeout] is -- `block` calls into a transport's
 * blocking socket I/O this `withTimeoutOrNull` cannot preempt; see [GATEWAY_CHECKIN_TIMEOUT_MS]'s
 * own doc. The transport's own per-read socket timeout is the real mechanism here too.
 */
internal suspend fun <T> withGatewayCheckinTimeout(block: suspend () -> T): T? =
    withTimeoutOrNull(GATEWAY_CHECKIN_TIMEOUT_MS) { block() }
