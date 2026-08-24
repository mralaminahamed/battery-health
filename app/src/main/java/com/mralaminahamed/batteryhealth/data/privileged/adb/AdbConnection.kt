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
 * A hand-rolled ADB client speaking the same TCP handshake `adb connect` does, so this app
 * no longer needs Shizuku running as a mediator. One socket, one CNXN/AUTH exchange; stream
 * multiplexing (OPEN/OKAY/WRTE/CLSE) is layered on top by `AdbStream` in a later task, which
 * is why [send] and [read] are exposed rather than kept private.
 */
class AdbConnection(
    private val host: String,
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
        val socket = try {
            val newSocket = Socket()
            newSocket.connect(InetSocketAddress(host, port), soTimeoutMs)
            newSocket.soTimeout = soTimeoutMs
            newSocket
        } catch (e: ConnectException) {
            return@withContext AdbConnectResult.Unreachable
        } catch (e: SocketTimeoutException) {
            return@withContext AdbConnectResult.Unreachable
        } catch (e: IOException) {
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
        if (header.command == A_CNXN) AdbConnectResult.Connected else AdbConnectResult.AwaitingAuthorization
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

    fun close() {
        runCatching { socket?.close() }
    }
}
