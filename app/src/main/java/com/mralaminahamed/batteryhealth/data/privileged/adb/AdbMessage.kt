package com.mralaminahamed.batteryhealth.data.privileged.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val A_CNXN = 0x4e584e43
const val A_AUTH = 0x48545541
const val A_OPEN = 0x4e45504f
const val A_OKAY = 0x59414b4f
const val A_CLSE = 0x45534c43
const val A_WRTE = 0x45545257

const val ADB_AUTH_TOKEN = 1
const val ADB_AUTH_SIGNATURE = 2
const val ADB_AUTH_RSAPUBLICKEY = 3

const val ADB_HEADER_BYTES = 24

data class AdbHeader(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val length: Int,
    val checksum: Int,
    val magic: Int,
)

class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray,
) {
    fun header(): ByteArray = ByteBuffer.allocate(ADB_HEADER_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(command)
        .putInt(arg0)
        .putInt(arg1)
        .putInt(payload.size)
        .putInt(checksum(payload))
        .putInt(command xor -1)
        .array()

    // Not a data class: a generated equals over a ByteArray compares the reference, which
    // is a surprising identity for a value type even if only tests ever compare these.
    override fun equals(other: Any?): Boolean = other is AdbMessage &&
        command == other.command && arg0 == other.arg0 && arg1 == other.arg1 &&
        payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        (((command * 31 + arg0) * 31 + arg1) * 31) + payload.contentHashCode()

    companion object {
        fun parseHeader(bytes: ByteArray): AdbHeader {
            require(bytes.size >= ADB_HEADER_BYTES) {
                "header is ${bytes.size} bytes, need $ADB_HEADER_BYTES"
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return AdbHeader(
                command = buffer.int,
                arg0 = buffer.int,
                arg1 = buffer.int,
                length = buffer.int,
                checksum = buffer.int,
                magic = buffer.int,
            )
        }

        /**
         * adb's own `data_check`: an unsigned sum of every payload byte, not a CRC despite
         * the field being transcribed as `data_crc32` in several protocol write-ups. The
         * `and 0xFF` is load-bearing -- Kotlin's Byte is signed, so a payload byte of 0x80
         * would subtract 128 instead of adding it, and the mismatch surfaces as an
         * authentication failure rather than anything pointing at framing.
         */
        fun checksum(payload: ByteArray): Int {
            var sum = 0
            for (byte in payload) sum += byte.toInt() and 0xFF
            return sum
        }
    }
}
