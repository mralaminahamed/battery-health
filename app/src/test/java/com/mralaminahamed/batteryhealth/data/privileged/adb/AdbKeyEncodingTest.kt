package com.mralaminahamed.batteryhealth.data.privileged.adb

import java.math.BigInteger
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbKeyEncodingTest {

    // A fixed 2048-bit odd modulus. Any odd 2048-bit integer exercises the same
    // arithmetic; a literal rather than a generated key keeps this deterministic.
    private val modulus = BigInteger(
        "c8f2a4b1e3d5079b6c4a2e8f0d19375b" + "8e2c6a4f0937d1b5e9c3a7f1d5b9e3c7".repeat(7),
        16,
    ).setBit(2047).setBit(0)

    private val exponent = BigInteger.valueOf(65537)

    @Test
    fun encodedStructIsFiveHundredTwentyFourBytes() {
        val encoded = encodeAndroidPublicKey(modulus, exponent, "batteryhealth@android")
        assertEquals(524, Base64.getDecoder().decode(encoded.substringBefore(' ')).size)
    }

    @Test
    fun userHostIsAppendedAfterASingleSpace() {
        val encoded = encodeAndroidPublicKey(modulus, exponent, "batteryhealth@android")
        assertTrue(encoded.endsWith(" batteryhealth@android"))
        assertEquals(1, encoded.count { it == ' ' })
    }

    @Test
    fun n0invIsTheNegatedModularInverseOfTheLowestWord() {
        val struct = decode(encodeAndroidPublicKey(modulus, exponent, "u@h"))
        val base = BigInteger.ONE.shiftLeft(32)
        assertEquals(modulus.modInverse(base).negate().mod(base).toLong(), readWord(struct, 1))
    }

    @Test
    fun modulusIsWrittenLeastSignificantWordFirst() {
        val struct = decode(encodeAndroidPublicKey(modulus, exponent, "u@h"))
        assertEquals(modulus.and(WORD_MASK).toLong(), readWord(struct, 2))
    }

    @Test
    fun rrIsRSquaredModN() {
        val struct = decode(encodeAndroidPublicKey(modulus, exponent, "u@h"))
        val rr = BigInteger.ONE.shiftLeft(4096).mod(modulus)
        assertEquals(rr.and(WORD_MASK).toLong(), readWord(struct, 2 + 64))
    }

    @Test
    fun exponentIsTheFinalWord() {
        val struct = decode(encodeAndroidPublicKey(modulus, exponent, "u@h"))
        assertEquals(65537L, readWord(struct, 2 + 64 + 64))
    }

    @Test
    fun signatureBlobIsSha1DigestInfoPrefixFollowedByTheRawToken() {
        val token = ByteArray(20) { it.toByte() }
        val blob = adbSignatureBlob(token)
        assertEquals(35, blob.size)
        assertArrayEquals(SHA1_DIGEST_INFO_PREFIX, blob.copyOfRange(0, 15))
        assertArrayEquals(token, blob.copyOfRange(15, 35))
    }

    private fun decode(encoded: String) = Base64.getDecoder().decode(encoded.substringBefore(' '))

    private fun readWord(struct: ByteArray, index: Int): Long {
        var value = 0L
        for (offset in 0 until 4) {
            value = value or ((struct[index * 4 + offset].toLong() and 0xFF) shl (8 * offset))
        }
        return value
    }

    private companion object {
        val WORD_MASK: BigInteger = BigInteger.valueOf(0xFFFFFFFFL)
    }
}
