package com.alaminahamed.batteryhealth.data.privileged.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbMessageTest {

    @Test
    fun headerIsTwentyFourLittleEndianBytes() {
        val message = AdbMessage(A_OPEN, arg0 = 1, arg1 = 0, payload = byteArrayOf(1, 2, 3))
        assertEquals(24, message.header().size)
    }

    @Test
    fun magicIsCommandXorMinusOne() {
        val message = AdbMessage(A_CNXN, arg0 = 0x01000000, arg1 = 256 * 1024, payload = ByteArray(0))
        val header = AdbMessage.parseHeader(message.header())
        assertEquals(A_CNXN xor -1, header.magic)
    }

    @Test
    fun checksumIsUnsignedSumOfPayloadBytesNotCrc32() {
        // 0x80 must contribute 128, not -128. A signed sum authenticates fine against
        // small ASCII payloads and fails only once a real dump carries a high byte.
        val message = AdbMessage(A_WRTE, arg0 = 1, arg1 = 2, payload = byteArrayOf(0x7F, 0x80.toByte()))
        val header = AdbMessage.parseHeader(message.header())
        assertEquals(0x7F + 0x80, header.checksum)
    }

    @Test
    fun headerRoundTripsEveryField() {
        val message = AdbMessage(A_WRTE, arg0 = 7, arg1 = 9, payload = "abc".toByteArray())
        val header = AdbMessage.parseHeader(message.header())
        assertEquals(A_WRTE, header.command)
        assertEquals(7, header.arg0)
        assertEquals(9, header.arg1)
        assertEquals(3, header.length)
    }

    @Test
    fun commandConstantsAreTheirAsciiLittleEndianValues() {
        assertEquals(0x4e584e43, A_CNXN)
        assertEquals(0x48545541, A_AUTH)
        assertEquals(0x4e45504f, A_OPEN)
        assertEquals(0x59414b4f, A_OKAY)
        assertEquals(0x45534c43, A_CLSE)
        assertEquals(0x45545257, A_WRTE)
    }

    @Test
    fun payloadSurvivesEncoding() {
        val payload = "shell:dumpsys battery\u0000".toByteArray()
        assertArrayEquals(payload, AdbMessage(A_OPEN, 1, 0, payload).payload)
    }
}
