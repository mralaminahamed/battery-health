package com.mralaminahamed.batteryhealth.data.privileged.adb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

private const val KEYSTORE = "AndroidKeyStore"
private const val ALIAS = "battery-health-adb"
private const val USER_HOST = "batteryhealth@android"

/**
 * What a signature over an adbd AUTH token is produced with. An interface rather than
 * [AdbKeyPair] directly because [AdbKeyPair] needs AndroidKeystore, which no JVM test can
 * construct -- the fake-daemon suite drives a plain `java.security` keypair instead.
 */
interface AdbSigner {
    fun publicKeyLine(): String
    fun sign(token: ByteArray): ByteArray
}

/**
 * The identity adbd authorizes once, in its "Allow USB debugging?" dialog, and remembers
 * afterwards.
 *
 * Held in AndroidKeystore rather than a file so the private key is non-exportable: this
 * key is what a shell is granted to, so an attacker with the app's data directory should
 * get something they cannot lift off the device.
 */
class AdbKeyPair private constructor(
    val publicKey: PublicKey,
    private val entry: KeyStore.PrivateKeyEntry,
) : AdbSigner {

    override fun publicKeyLine(): String {
        val rsa = publicKey as RSAPublicKey
        return encodeAndroidPublicKey(rsa.modulus, rsa.publicExponent, USER_HOST)
    }

    /** See [adbSignatureBlob] for why the token is prefixed rather than hashed again. */
    override fun sign(token: ByteArray): ByteArray = Signature.getInstance("NONEwithRSA").run {
        initSign(entry.privateKey)
        update(adbSignatureBlob(token))
        sign()
    }

    companion object {
        fun loadOrCreate(): AdbKeyPair {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val entry = (store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)
                ?: run {
                    generate()
                    store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
                }
            return AdbKeyPair(entry.certificate.publicKey, entry)
        }

        private fun generate() {
            // DIGEST_NONE is the load-bearing part: adbd's token is already a digest, so
            // Keystore has to be willing to sign it raw. A key generated with SHA-1
            // digests instead refuses the NONEwithRSA signature at use time, not here.
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).run {
                initialize(spec)
                generateKeyPair()
            }
        }
    }
}
