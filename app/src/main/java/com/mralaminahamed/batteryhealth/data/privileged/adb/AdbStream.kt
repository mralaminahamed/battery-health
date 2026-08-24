package com.mralaminahamed.batteryhealth.data.privileged.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 4 MiB: generous for any `dumpsys`/`getprop` output this app asks for, small enough that a
 * runaway or misbehaving responder can't be turned into unbounded heap growth on our side.
 */
const val MAX_DUMP_BYTES = 4 * 1024 * 1024

// Stream ids only need to be unique within a single TCP connection, and AdbConnection is not
// to be restructured to carry a per-connection counter for this -- so this is process-wide.
// Sharing it across connections burns through the id space faster than necessary but is
// never wrong: adbd's own bookkeeping is keyed by (connection, local id) already.
private val nextStreamId = AtomicInteger(1)

/**
 * Opens an `adb shell` stream (OPEN), drains it under flow control (WRTE/OKAY) until the
 * remote closes it (CLSE), and hands back the decoded output. This is the only place in the
 * client that speaks the stream sub-protocol -- [AdbConnection.send]/[AdbConnection.read]
 * stay transport-only plumbing, per that class's own doc comment.
 *
 * Every failure -- a socket problem, a blank result, or the daemon simply sending more than
 * [maxBytes] -- collapses to null rather than throwing. A privileged shell command failing
 * (adbd not running, permission denied, device gone) is a routine outcome a caller has to
 * handle either way, not exceptional control flow.
 */
suspend fun AdbConnection.shell(command: String, maxBytes: Int = MAX_DUMP_BYTES): String? =
    withContext(Dispatchers.IO) {
        val localId = nextStreamId.getAndIncrement()
        try {
            send(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray())
            var remoteId = 0
            val buffer = ByteArrayOutputStream()
            while (true) {
                val (header, payload) = read()
                when (header.command) {
                    A_OKAY -> remoteId = header.arg0
                    A_WRTE -> {
                        buffer.write(payload)
                        // Ack before the size check, always. adbd is blocked mid-WRTE
                        // waiting on this OKAY; bailing out for being oversize before
                        // sending it would leave that write -- and the daemon -- hanging
                        // instead of tearing the stream down cleanly. Do not reorder this.
                        send(A_OKAY, localId, remoteId, ByteArray(0))
                        if (buffer.size() > maxBytes) return@withContext null
                    }
                    A_CLSE -> break
                }
            }
            buffer.toString(Charsets.UTF_8.name()).takeIf { it.isNotBlank() }
        } catch (e: IOException) {
            null
        }
    }
