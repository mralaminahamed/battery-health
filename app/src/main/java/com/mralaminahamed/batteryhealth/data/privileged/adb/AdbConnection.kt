package com.mralaminahamed.batteryhealth.data.privileged.adb

import java.io.DataInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AdbConnectResult { Connected, AwaitingAuthorization, Unreachable, Failed }

/**
 * The only address this client will ever dial. Declared here, not in a shared constants
 * file, because [AdbConnection] is the only class in this app that opens a [Socket] --
 * keeping the constant next to the one call site that can use it is what lets a later
 * enforcement test grep every `Socket(`-bearing file for it and actually mean something.
 * Defaulted on [host] rather than left as a caller convention: a transport that forgot to
 * pass it would otherwise silently be free to dial anything.
 */
const val LOOPBACK_HOST = "127.0.0.1"

/**
 * A hand-rolled ADB client speaking the same TCP handshake `adb connect` does, so this app
 * needs no separate privileged helper app running as a mediator. One socket, one CNXN/AUTH
 * exchange; stream multiplexing (OPEN/OKAY/WRTE/CLSE) is layered on top by `AdbStream` in a
 * later task, which is why [send] and [read] are exposed rather than kept private.
 */
class AdbConnection(
    private val host: String = LOOPBACK_HOST,
    private val port: Int,
    private val signer: AdbSigner,
    private val soTimeoutMs: Int,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null

    suspend fun connect(): AdbConnectResult = withContext(Dispatchers.IO) {
        // Deliberately not `Socket().apply { connect(...) }`: apply's receiver is the new
        // Socket, and Socket itself declares a `port` property (the remote port, 0 until
        // connected) that would silently shadow the outer AdbConnection.port field of the
        // same name -- every connection would target port 0 instead of the real one.
        val newSocket = Socket()
        val socket = try {
            newSocket.connect(InetSocketAddress(host, port), soTimeoutMs)
            newSocket.soTimeout = soTimeoutMs
            // OPEN/OKAY/WRTE/CLSE is strictly lockstep -- every write already waits on the
            // previous round trip, so there is nothing for Nagle's coalescing to buy us.
            // Left at its default, Nagle plus the peer's delayed-ACK timer taxes every single
            // small header-sized message by tens of milliseconds; across a long chunked dump
            // that is seconds of pure protocol overhead for zero benefit.
            newSocket.tcpNoDelay = true
            newSocket
        } catch (e: ConnectException) {
            runCatching { newSocket.close() }
            return@withContext AdbConnectResult.Unreachable
        } catch (e: SocketTimeoutException) {
            runCatching { newSocket.close() }
            return@withContext AdbConnectResult.Unreachable
        } catch (e: IOException) {
            runCatching { newSocket.close() }
            return@withContext AdbConnectResult.Failed
        }
        this@AdbConnection.socket = socket
        this@AdbConnection.input = DataInputStream(socket.getInputStream())

        // A timeout here is deliberately *not* mapped to AwaitingAuthorization: that result
        // is reserved for the one place the protocol expects a long, user-paced wait --
        // after the public key has been offered and the device is showing its dialog. A
        // timeout anywhere else in the handshake (e.g. adbd never sending the first TOKEN)
        // is a plain failure, not "go check the phone".
        try {
            send(A_CNXN, 0x01000000, 256 * 1024, "host::features=cmd\u0000".toByteArray())
            handshake()
        } catch (e: IOException) {
            AdbConnectResult.Failed
        }
    }

    // adbd cannot distinguish "signature we rejected" from "key we have never seen before"
    // -- both look like the client getting another TOKEN instead of a CNXN. It resolves
    // both the same way on a real device: offer the public key and let the user answer the
    // "Allow USB debugging?" dialog. So a second TOKEN here is read the same way, not as a
    // retry worth signing again.
    private fun handshake(): AdbConnectResult {
        var tokenSeen = 0
        while (true) {
            val (header, payload) = read()
            when {
                header.command == A_AUTH && header.arg0 == ADB_AUTH_TOKEN -> {
                    tokenSeen += 1
                    if (tokenSeen == 1) {
                        send(A_AUTH, ADB_AUTH_SIGNATURE, 0, signer.sign(payload))
                    } else {
                        send(
                            A_AUTH,
                            ADB_AUTH_RSAPUBLICKEY,
                            0,
                            (signer.publicKeyLine() + "\u0000").toByteArray(),
                        )
                        return awaitConnectionOrTimeout()
                    }
                }
                header.command == A_CNXN -> return AdbConnectResult.Connected
            }
        }
    }

    /**
     * Having offered the public key, the device is now showing its authorization dialog.
     * There is no message that says "still waiting" -- the wait itself is the signal, so
     * this blocks on the next [read] until either a CNXN arrives (the user tapped Allow) or
     * the socket's own timeout fires first (nobody answered in time).
     */
    private fun awaitConnectionOrTimeout(): AdbConnectResult = try {
        val (header, _) = read()
        // AwaitingAuthorization is reserved for the timeout case below -- it means "the
        // device is still deciding." A reply that arrives but isn't CNXN is a protocol
        // violation, not "still waiting", so it is treated as a plain failure rather than
        // silently kept waiting for a CNXN that this daemon has already declined to send.
        if (header.command == A_CNXN) AdbConnectResult.Connected else AdbConnectResult.Failed
    } catch (e: SocketTimeoutException) {
        AdbConnectResult.AwaitingAuthorization
    }

    internal fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val output = requireNotNull(socket) { "not connected" }.getOutputStream()
        output.write(AdbMessage(command, arg0, arg1, payload).header())
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    internal fun read(): Pair<AdbHeader, ByteArray> {
        val stream = requireNotNull(input) { "not connected" }
        val headerBytes = ByteArray(ADB_HEADER_BYTES)
        stream.readFully(headerBytes)
        val header = AdbMessage.parseHeader(headerBytes)
        val payload = ByteArray(header.length)
        if (header.length > 0) stream.readFully(payload)
        return header to payload
    }

    /**
     * Narrows this connection's per-read socket timeout to [timeoutMs] for the duration of
     * [block], then restores whatever it was. [soTimeoutMs] (the constructor parameter) is
     * this connection's *default* -- set once on the socket by [connect] and otherwise left
     * in force for every later read -- which is right for the handshake (a long, user-paced
     * wait for someone to tap "Allow") but wrong for a caller needing a shorter, call-
     * specific bound on a read that happens well after the handshake is done. [send]/[read]
     * are plain blocking calls with no suspension point, so a coroutine-level timeout like
     * `withTimeoutOrNull` cannot preempt a thread parked inside one of them -- only the
     * socket's own read timeout actually cuts a stalled read off at [timeoutMs]. Restoring
     * the previous value afterward, rather than leaving [timeoutMs] in force, is what lets
     * this be called repeatedly with a different bound per call on the same connection.
     */
    internal suspend fun <T> withSoTimeout(timeoutMs: Int, block: suspend () -> T): T {
        val activeSocket = requireNotNull(socket) { "not connected" }
        val previous = activeSocket.soTimeout
        activeSocket.soTimeout = timeoutMs
        try {
            return block()
        } finally {
            runCatching { activeSocket.soTimeout = previous }
        }
    }

    fun close() {
        runCatching { socket?.close() }
    }
}
