package com.alaminahamed.batteryhealth.data.privileged.adb

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
    fun n0invSatisfiesTheDefiningProperty() {
        // n0inv is the precomputed value (-1/n) mod 2^32 such that (n * n0inv + 1) mod 2^32 == 0.
        // Rather than recompute the modInverse call, assert the property it must satisfy.
        val struct = decode(encodeAndroidPublicKey(modulus, exponent, "u@h"))
        val n0inv = readWord(struct, 1).toBigInteger()
        val base = BigInteger.ONE.shiftLeft(32)
        val product = modulus.multiply(n0inv).add(BigInteger.ONE).mod(base)
        assertEquals("n0inv property violated", BigInteger.ZERO, product)
    }

    @Test
    fun modulusIsWrittenLeastSignificantWordFirstAndRoundTrips() {
        val struct = decode(encodeAndroidPublicKey(modulus, exponent, "u@h"))
        // Verify the first word is the least-significant word of the modulus.
        assertEquals(modulus.and(WORD_MASK).toLong(), readWord(struct, 2))
        // Reconstruct the full modulus from all 64 words and assert round-trip.
        val reconstructed = reconstructBigInteger(struct, 2, 64)
        assertEquals(modulus, reconstructed)
    }

    @Test
    fun rrIsRSquaredModNWithIndependentDerivation() {
        // Rather than recompute shiftLeft(4096).mod(n), verify the defining property:
        // rr should equal (R mod n)^2 mod n, where R = 2^2048.
        val struct = decode(encodeAndroidPublicKey(modulus, exponent, "u@h"))
        val rr = reconstructBigInteger(struct, 2 + 64, 64)
        val r = BigInteger.ONE.shiftLeft(2048).mod(modulus)
        val rSquaredModN = r.multiply(r).mod(modulus)
        assertEquals(rSquaredModN, rr)
    }

    @Test
    fun exponentIsTheFinalWord() {
        val struct = decode(encodeAndroidPublicKey(modulus, exponent, "u@h"))
        assertEquals(65537L, readWord(struct, 2 + 64 + 64))
    }

    @Test
    fun goldenVectorMatchesAdbOwnEncoding() {
        // adb keygen ground truth: adb's own implementation of android_pubkey encoding.
        // Produced via: adb keygen /tmp/bh-golden-key
        // Modulus extracted via: openssl rsa -in /tmp/bh-golden-key -noout -modulus
        val goldenModulus = BigInteger(
            "D381962AC809300A3361D185A08B7D2E76153DAEA6D61CB10561E8B8F07003A21" +
            "F51CD415FA8A4C4BF223A28879E01ED6A849D30F6BC23808A52F1B4A59173AAC5F" +
            "92689731953AF8C9426D237944C943B7421A3AF3AA3B54FCBF155162B04ECBC0D8" +
            "B85799CDC914905AEEAD0D168B30449D642317A17ACB04E1C392926E9921EEB44E2" +
            "D10F1DCDE2AFA76B9DAC7727B1AB622F083A37E165523144D787257A41402AE1EB" +
            "3C84A0E7C961DE8AE33DBAD5CBC19098920131F6E149C2B8343EAB9E955AECC2D1C" +
            "A32D825217CE14E75AF582F21F612B051858F437008E1EAC59BA13D69AF0B23AAC5" +
            "AC34B59B609E8BBE3A6F64CF5E7F40AAFEB32777F162133D",
            16
        )
        val goldenBase64 = "QAAAAOsjI/U9E2Lxdyez/qpAf17PZG86voueYJu1NKzFqiMLr2k9oZvF6uEIcEOPhVGwEvYhL1ivdU7hfCEl2DLK0cLsWpWeqz40uMJJ4fYxAZKYkMHL1bo944reYcnnoIQ86+EqQEF6JYfXRDFSZeE3OggvYquxJ3esnWunr+LNHQ/R4kTrHpLpJik5HE6wrBd6MULWSQSzaNHQ6q4FSZHcnHmFiw287AQrFlXxy0+1ozqvoyF0O5RMlDfSJpSMr1MZc4km+cWqc5GltPFSioAjvPYwnYRq7QGehyg6Ir/EpKhfQc1RH6IDcPC46GEFsRzWpq49FXYufYughdFhMwowCcgqloHT1wFQi5g0Di5isRPuy5K4Z50BItqRbgNVKTl4/rl3VRErqtwvznAtASUtpn2X/4ewCA97kISeVi/AGhr4MA8oZ7FH156AJxL+lVfpR6YCeKWFOSQs83f8huI1sjoHkbYlG7COMYAh0evA+toSzsfQEU0av+CoMXnlq3A5fZ49+1H66RgIZTXXpii6C8oiG6VyxIoovBWSvrzwclcC4Z45PIbpJ151k9OIG9xjbg7gElndMkyk0dJYMRS4zGSa2i9FwKmFHYe2YSREhDeXxdRKd6QImk/ahoYxifShNBCPCy3otL0bA1oYF1+EAmJzR+4CBOLPl6o4Vu9k8iI+JH/TfQEAAQA="

        val encoded = encodeAndroidPublicKey(goldenModulus, exponent, "test@android")
        val encodedBase64 = encoded.substringBefore(' ')
        assertEquals("golden vector struct mismatch", goldenBase64, encodedBase64)
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

    private fun reconstructBigInteger(struct: ByteArray, startWordIndex: Int, wordCount: Int): BigInteger {
        // Reconstruct a BigInteger from little-endian words: LSW first.
        var result = BigInteger.ZERO
        for (i in 0 until wordCount) {
            val word = readWord(struct, startWordIndex + i).toBigInteger()
            result = result.add(word.shiftLeft(32 * i))
        }
        return result
    }

    private companion object {
        val WORD_MASK: BigInteger = BigInteger.valueOf(0xFFFFFFFFL)
    }
}
