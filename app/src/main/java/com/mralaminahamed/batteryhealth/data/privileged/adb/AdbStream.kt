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
                // arg1 is the recipient's local id for A_OKAY/A_WRTE/A_CLSE -- i.e. the
                // stream this message is addressed to, not the sender's own id (that's
                // arg0). This connection is shared and reused across calls, so residue from
                // a stream this same connection never cleanly closed (or, absent the Mutex
                // that serializes calls one level up in AdbShell, a genuinely concurrent
                // call) can arrive interleaved with this call's own traffic. Anything not
                // addressed to localId must be drained and ignored here, never folded into
                // this call's buffer or acked as if it were this call's own.
                when (header.command) {
                    A_OKAY -> if (header.arg1 == localId) remoteId = header.arg0
                    A_WRTE -> if (header.arg1 == localId) {
                        buffer.write(payload)
                        // Ack before the size check, always. adbd is blocked mid-WRTE
                        // waiting on this OKAY; bailing out for being oversize before
                        // sending it would leave that write -- and the daemon -- hanging
                        // instead of tearing the stream down cleanly. Do not reorder this.
                        send(A_OKAY, localId, remoteId, ByteArray(0))
                        if (buffer.size() > maxBytes) {
                            // Tear the stream down instead of merely abandoning it: without
                            // this, adbd keeps writing for a stream nobody is reading, and
                            // the *next* shell() call on this same connection would have to
                            // filter that residue out instead of never seeing it at all.
                            send(A_CLSE, localId, remoteId, ByteArray(0))
                            return@withContext null
                        }
                    }
                    A_CLSE -> if (header.arg1 == localId) break
                    else -> {
                        // Not part of the stream sub-protocol once the connection is
                        // established (e.g. a stray A_CNXN/A_AUTH). Drain and ignore rather
                        // than let an unhandled command corrupt this call's buffer or crash
                        // the stream -- this branch was the missing default the `when` above
                        // never had.
                    }
                }
            }
            buffer.toString(Charsets.UTF_8.name()).takeIf { it.isNotBlank() }
        } catch (e: IOException) {
            null
        }
    }
