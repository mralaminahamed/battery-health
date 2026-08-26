package com.alaminahamed.batteryhealth.data.privileged.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbKeyPairTest {

    @Test
    fun publicKeyLineIsAFiveHundredTwentyFourByteStructPlusUserHost() {
        val line = AdbKeyPair.loadOrCreate().publicKeyLine()
        assertEquals(524, Base64.getDecoder().decode(line.substringBefore(' ')).size)
        assertTrue(line.contains(' '))
    }

    @Test
    fun signatureVerifiesAgainstThePublicKeyUnderNoneWithRsa() {
        // The point of this task: if Keystore refuses DIGEST_NONE, it shows up here on
        // hardware rather than at first contact with a real adbd.
        val keyPair = AdbKeyPair.loadOrCreate()
        val token = ByteArray(20) { (it * 7).toByte() }
        val signature = keyPair.sign(token)

        val verifier = Signature.getInstance("NONEwithRSA")
        verifier.initVerify(keyPair.publicKey)
        verifier.update(adbSignatureBlob(token))
        assertTrue(verifier.verify(signature))
    }

    @Test
    fun loadOrCreateIsStableAcrossCalls() {
        assertEquals(
            AdbKeyPair.loadOrCreate().publicKeyLine(),
            AdbKeyPair.loadOrCreate().publicKeyLine(),
        )
    }

    @Test
    fun signatureIsTwoHundredFiftySixBytesForRsa2048() {
        assertEquals(256, AdbKeyPair.loadOrCreate().sign(ByteArray(20)).size)
    }
}
