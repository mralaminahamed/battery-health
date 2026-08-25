package com.alaminahamed.batteryhealth.data.privileged.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

private const val RSA_2048_WORDS = 64
private const val ANDROID_PUBKEY_BYTES = 524
private val WORD_MASK: BigInteger = BigInteger.valueOf(0xFFFFFFFFL)

/**
 * The ASN.1 DigestInfo header for SHA-1, prepended to an already-hashed value so a raw
 * PKCS#1 v1.5 signature over it matches what adbd verifies.
 */
val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
    0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
)

/**
 * adbd's AUTH token is already a 20-byte digest, so the signature must be produced with
 * `NONEwithRSA` over this prefixed blob. Signing the token with `SHA1withRSA` hashes it a
 * second time; adbd then rejects the signature and re-sends a TOKEN, which presents as an
 * endless auth loop rather than as anything naming the real cause.
 */
fun adbSignatureBlob(token: ByteArray): ByteArray = SHA1_DIGEST_INFO_PREFIX + token

/**
 * adb's own public key wire format: not PEM, not X.509, but a fixed 524-byte struct of
 * little-endian 32-bit words, base64-encoded with a trailing " user@host" that the device
 * shows in its authorization dialog.
 *
 * `n0inv` and `rr` are precomputed here because adbd verifies with Montgomery
 * multiplication and expects both in the key rather than deriving them itself.
 */
fun encodeAndroidPublicKey(
    modulus: BigInteger,
    exponent: BigInteger,
    userHost: String,
): String {
    val buffer = ByteBuffer.allocate(ANDROID_PUBKEY_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    val base = BigInteger.ONE.shiftLeft(32)
    val n0inv = modulus.modInverse(base).negate().mod(base)
    // R = 2^2048 for a 2048-bit modulus; adbd wants R^2 mod n.
    val rr = BigInteger.ONE.shiftLeft(RSA_2048_WORDS * 32 * 2).mod(modulus)

    buffer.putInt(RSA_2048_WORDS)
    buffer.putInt(n0inv.toLong().toInt())
    putWords(buffer, modulus)
    putWords(buffer, rr)
    buffer.putInt(exponent.toInt())

    return Base64.getEncoder().encodeToString(buffer.array()) + " " + userHost
}

/** Least-significant word first, zero-padded to exactly [RSA_2048_WORDS]. */
private fun putWords(buffer: ByteBuffer, value: BigInteger) {
    var remaining = value
    repeat(RSA_2048_WORDS) {
        buffer.putInt(remaining.and(WORD_MASK).toLong().toInt())
        remaining = remaining.shiftRight(32)
    }
}
