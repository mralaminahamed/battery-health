# Privileged Tier Without Shizuku — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Shizuku dependency and the separate Shizuku app with an in-app ADB client plus a root path, so the privileged battery tier works with no third-party dependency and no external app.

**Architecture:** Two transports (`AdbShell` over a hand-written ADB protocol client, `RootShell` over `su -c`) implement one `PrivilegedShell` seam. `AdbGateway` owns both, reduces their states into `PrivilegedAvailability`, and implements the existing `PrivilegedBatterySource` interface that `BatteryRepository` and the ViewModels already depend on. No `app_process` server, no binder, no AIDL — the shell surface is a two-command allowlist enforced structurally by the seam having no method that accepts a command.

**Tech Stack:** Kotlin, coroutines, Hilt, AndroidKeystore, `java.net.Socket`, `java.math.BigInteger`, JUnit 4, `kotlinx-coroutines-test`, Compose UI test.

**Spec:** `docs/design/2026-08-24-privileged-tier-design.md`

## Global Constraints

- `minSdk = 26`, `targetSdk = 37`, `compileSdk = 37`. The unprivileged tier must keep working across that whole range.
- **No third-party dependencies.** AndroidX/Google only. Do not add any library to `gradle/libs.versions.toml` for this work.
- **Shell allowlist is structural.** `PrivilegedShell` exposes exactly `runDump()` and `runCheckin()`. Never add a method taking a command string, not even in debug builds.
- Command strings are `const val`. Nothing derived from user input, settings, or parsed data reaches a shell.
- Nothing throws across `PrivilegedBatterySource`. Every failure becomes `null`.
- No background reconnect loop. Retry only on `refresh()`.
- **ADB protocol strings are NUL-terminated.** Write the terminator as the Kotlin escape `\u0000`, never as a literal control character in source.
- Every Gradle task is flavour-qualified: `testPlayDebugUnitTest`, `connectedPlayDebugAndroidTest`, `assemblePlayDebug`.
- `connectedPlayDebugAndroidTest` does **not** accept `--tests`. Filter with `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`. It also uninstalls the app when it finishes.
- Device must be awake and unlocked or Compose tests fail with the misleading "No compose hierarchies found". Run `adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard` first.
- If no system JDK is on `PATH`, point `JAVA_HOME` at Android Studio's bundled `jbr`.
- **Commit message style — match the repo, not this skill's examples.** Imperative, sentence case, no `feat:`/`fix:` prefix. e.g. `Add the ADB wire message codec`.
- Existing rationale-comment density is high. Match it: explain *why*, not *what*. Do not add comments that restate the code.

---

### Task 1: ADB wire message codec

**Files:**
- Create: `app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbMessage.kt`
- Test: `app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbMessageTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `AdbMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray)`; `AdbMessage.header(): ByteArray`; `AdbMessage.Companion.parseHeader(bytes: ByteArray): AdbHeader`; `AdbHeader(command, arg0, arg1, length, checksum, magic)`; command constants `A_CNXN`, `A_AUTH`, `A_OPEN`, `A_OKAY`, `A_CLSE`, `A_WRTE`; auth constants `ADB_AUTH_TOKEN = 1`, `ADB_AUTH_SIGNATURE = 2`, `ADB_AUTH_RSAPUBLICKEY = 3`; `ADB_HEADER_BYTES = 24`.

**Key fact:** the sixth header word is `magic == command xor -1`. The fifth is **not** a CRC — adb computes an unsigned sum of every payload byte. Reaching for `java.util.zip.CRC32` here produces an authentication failure that points nowhere near framing.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged.adb

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbMessageTest*"`
Expected: FAIL — unresolved reference `AdbMessage`, `A_CNXN`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbMessageTest*"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbMessage.kt \
        app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbMessageTest.kt
git commit -m "Add the ADB wire message codec"
```

---

### Task 2: ADB public-key encoding and signature blob

**Files:**
- Create: `app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbKeyEncoding.kt`
- Test: `app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbKeyEncodingTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `encodeAndroidPublicKey(modulus: BigInteger, exponent: BigInteger, userHost: String): String`; `adbSignatureBlob(token: ByteArray): ByteArray`; `SHA1_DIGEST_INFO_PREFIX: ByteArray`.

**Separate from keypair management** because it is pure arithmetic with no Android dependency, so it is fully JVM-testable. The keypair itself needs AndroidKeystore and is Task 3.

**The struct**, all little-endian, 524 bytes before base64:

```
uint32  modulus_size_words   = 64 for RSA-2048
uint32  n0inv                = (-1 / n) mod 2^32, as an unsigned 32-bit value
uint32  modulus[64]          little-endian words, least significant word first
uint32  rr[64]               R^2 mod n, where R = 2^2048, same word order
uint32  exponent             65537
```

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbKeyEncodingTest*"`
Expected: FAIL — unresolved reference `encodeAndroidPublicKey`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged.adb

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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbKeyEncodingTest*"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Cross-check against a real adbkey, once**

If a desktop `~/.android/adbkey.pub` exists, confirm the struct length matches this encoder's:

```bash
python3 -c "import base64,os;p=os.path.expanduser('~/.android/adbkey.pub');print(len(base64.b64decode(open(p).read().split()[0])))"
```
Expected: `524`. Record the outcome in the commit message. If no desktop key exists, say so and rely on the arithmetic tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbKeyEncoding.kt \
        app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbKeyEncodingTest.kt
git commit -m "Add adb public key encoding and signature blob"
```

---

### Task 3: Keystore-backed ADB identity key

**Files:**
- Create: `app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbKeyPair.kt`
- Test: `app/src/androidTest/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbKeyPairTest.kt`

**Interfaces:**
- Consumes: `encodeAndroidPublicKey`, `adbSignatureBlob` (Task 2).
- Produces: `interface AdbSigner { fun publicKeyLine(): String; fun sign(token: ByteArray): ByteArray }`; `class AdbKeyPair : AdbSigner` with `val publicKey: PublicKey` and `AdbKeyPair.Companion.loadOrCreate(): AdbKeyPair`.

**Instrumented, not JVM:** this is the spec's flagged risk — AndroidKeystore may reject `NONEwithRSA` with `DIGEST_NONE` on some OEM builds. It can only be proven on hardware.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged.adb

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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
./gradlew connectedPlayDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mralaminahamed.batteryhealth.data.privileged.adb.AdbKeyPairTest
```
Expected: FAIL — unresolved reference `AdbKeyPair`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Re-run the `connectedPlayDebugAndroidTest` command from Step 2.
Expected: PASS, 4 tests.

**If `signatureVerifiesAgainstThePublicKeyUnderNoneWithRsa` fails** with `NoSuchAlgorithmException` or `InvalidKeyException`, the OEM Keystore refuses `DIGEST_NONE`. **Stop and report.** The fallback is an app-private key file in `context.filesDir` — no weaker than desktop adb's plaintext `adbkey` — but taking that route is a decision to raise, not to make silently.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbKeyPair.kt \
        app/src/androidTest/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbKeyPairTest.kt
git commit -m "Add the Keystore-backed adb identity key"
```

---

### Task 4: Fake adbd and the connection handshake

**Files:**
- Create: `app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/FakeAdbDaemon.kt`
- Create: `app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbConnection.kt`
- Test: `app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbConnectionTest.kt`

**Interfaces:**
- Consumes: `AdbMessage` and the command/auth constants (Task 1); `adbSignatureBlob`, `encodeAndroidPublicKey` (Task 2); `AdbSigner` (Task 3).
- Produces: `class AdbConnection(host: String, port: Int, signer: AdbSigner, soTimeoutMs: Int)` with `suspend fun connect(): AdbConnectResult`, `fun close()`, and `internal` `send`/`read`; `enum class AdbConnectResult { Connected, AwaitingAuthorization, Unreachable, Failed }`.

**Why a protocol-speaking fake rather than a mock:** the bugs this suite exists to catch — an unacked `WRTE` stalling a dump, a signature adbd would reject, a stream never closed — are all invisible to a mock and all reproducible here without a device.

- [ ] **Step 1: Write the fake daemon**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged.adb

import java.io.DataInputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class FakeAdbDaemon(
    private val knownPublicKeyLine: String? = null,
    private val shellResponses: Map<String, ByteArray> = emptyMap(),
    private val writeChunkSize: Int = 8,
) {
    private val server = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool()

    /** Set once the client answered a TOKEN with a signature this daemon accepted. */
    @Volatile var authorized: Boolean = false; private set

    /** Set once the client offered its public key -- the "Allow USB debugging?" path. */
    @Volatile var receivedPublicKey: String? = null; private set

    /** Every ack the client sent, so a test can assert flow control was honoured. */
    val acks: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    val port: Int get() = server.localPort

    fun start() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                pool.execute { runCatching { serve(socket) } }
            }
        }
    }

    fun stop() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun serve(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()

        fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
            output.write(AdbMessage(command, arg0, arg1, payload).header())
            if (payload.isNotEmpty()) output.write(payload)
            output.flush()
        }

        fun read(): Pair<AdbHeader, ByteArray> {
            val headerBytes = ByteArray(ADB_HEADER_BYTES)
            input.readFully(headerBytes)
            val header = AdbMessage.parseHeader(headerBytes)
            val payload = ByteArray(header.length)
            if (header.length > 0) input.readFully(payload)
            return header to payload
        }

        val token = ByteArray(20) { it.toByte() }
        val (first, _) = read()
        require(first.command == A_CNXN) { "expected CNXN first, got ${first.command}" }
        send(A_AUTH, ADB_AUTH_TOKEN, 0, token)

        while (true) {
            val (header, payload) = read()
            when {
                header.command == A_AUTH && header.arg0 == ADB_AUTH_SIGNATURE -> {
                    if (knownPublicKeyLine != null && verifies(knownPublicKeyLine, token, payload)) {
                        authorized = true
                        send(A_CNXN, 0x01000000, 256 * 1024, "device::features=cmd\u0000".toByteArray())
                    } else {
                        send(A_AUTH, ADB_AUTH_TOKEN, 0, token)
                    }
                }
                header.command == A_AUTH && header.arg0 == ADB_AUTH_RSAPUBLICKEY -> {
                    receivedPublicKey = String(payload).trimEnd('\u0000')
                    authorized = true
                    send(A_CNXN, 0x01000000, 256 * 1024, "device::features=cmd\u0000".toByteArray())
                }
                header.command == A_OPEN -> {
                    val destination = String(payload).trimEnd('\u0000')
                    val remoteId = 1
                    val localId = header.arg0
                    send(A_OKAY, remoteId, localId)
                    val body = shellResponses[destination] ?: ByteArray(0)
                    // Chunked on purpose: a client that does not ack each WRTE stalls here
                    // rather than silently passing on a single-chunk payload.
                    body.toList().chunked(writeChunkSize).forEach { chunk ->
                        send(A_WRTE, remoteId, localId, chunk.toByteArray())
                        val (ack, _) = read()
                        acks += ack.command
                        require(ack.command == A_OKAY) { "client did not ack WRTE" }
                    }
                    send(A_CLSE, remoteId, localId)
                }
                header.command == A_CLSE -> return
            }
        }
    }

    private fun verifies(publicKeyLine: String, token: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val verifier = Signature.getInstance("NONEwithRSA")
            verifier.initVerify(publicKeyFromLine(publicKeyLine))
            verifier.update(adbSignatureBlob(token))
            verifier.verify(signature)
        }.getOrDefault(false)

    companion object {
        /** A host-side signer, since AndroidKeystore is unavailable on the JVM. */
        fun signer(): Pair<AdbSigner, String> {
            val pair = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
            val rsa = pair.public as RSAPublicKey
            val line = encodeAndroidPublicKey(rsa.modulus, rsa.publicExponent, "test@host")
            val signer = object : AdbSigner {
                override fun publicKeyLine() = line
                override fun sign(token: ByteArray): ByteArray =
                    Signature.getInstance("NONEwithRSA").run {
                        initSign(pair.private)
                        update(adbSignatureBlob(token))
                        sign()
                    }
            }
            return signer to line
        }

        /** Reverses [encodeAndroidPublicKey] so this daemon can verify like adbd does. */
        private fun publicKeyFromLine(line: String): PublicKey {
            val struct = Base64.getDecoder().decode(line.substringBefore(' '))
            val buffer = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
            val words = buffer.int
            buffer.int // n0inv, not needed to verify
            var modulus = BigInteger.ZERO
            for (index in 0 until words) {
                val word = BigInteger.valueOf(buffer.int.toLong() and 0xFFFFFFFFL)
                modulus = modulus.or(word.shiftLeft(index * 32))
            }
            repeat(words) { buffer.int } // rr, not needed to verify
            val exponent = BigInteger.valueOf(buffer.int.toLong() and 0xFFFFFFFFL)
            return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        }
    }
}
```

- [ ] **Step 2: Write the failing connection test**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged.adb

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbConnectionTest {

    private var daemon: FakeAdbDaemon? = null

    @After fun tearDown() { daemon?.stop() }

    @Test
    fun connectsWhenTheDaemonAlreadyKnowsTheKey() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(knownPublicKeyLine = line).also { daemon = it; it.start() }

        val result = AdbConnection("127.0.0.1", fake.port, signer, soTimeoutMs = 2_000).connect()

        assertEquals(AdbConnectResult.Connected, result)
        assertTrue(fake.authorized)
    }

    @Test
    fun offersThePublicKeyWhenTheDaemonDoesNotKnowIt() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(knownPublicKeyLine = null).also { daemon = it; it.start() }

        AdbConnection("127.0.0.1", fake.port, signer, soTimeoutMs = 2_000).connect()

        // The path that raises "Allow USB debugging?" on a real device.
        assertEquals(line, fake.receivedPublicKey)
    }

    @Test
    fun reportsUnreachableWhenNothingIsListening() = runTest {
        val closed = FakeAdbDaemon().also { it.start() }
        val port = closed.port
        closed.stop()

        val result = AdbConnection(
            "127.0.0.1", port, FakeAdbDaemon.signer().first, soTimeoutMs = 500,
        ).connect()

        assertEquals(AdbConnectResult.Unreachable, result)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbConnectionTest*"`
Expected: FAIL — unresolved reference `AdbConnection`.

- [ ] **Step 4: Implement `AdbConnection`**

Write it with:

- `connect()` on `Dispatchers.IO`. Open a `Socket()`, `connect(InetSocketAddress(host, port), soTimeoutMs)`, then `soTimeout = soTimeoutMs`. Send `A_CNXN(0x01000000, 256 * 1024, "host::features=cmd\u0000".toByteArray())`.
- Then loop on `read()`:
  - `A_AUTH` with `arg0 == ADB_AUTH_TOKEN`, first occurrence → reply `A_AUTH(ADB_AUTH_SIGNATURE, 0, signer.sign(payload))`.
  - `A_AUTH` with `arg0 == ADB_AUTH_TOKEN`, second occurrence → reply `A_AUTH(ADB_AUTH_RSAPUBLICKEY, 0, (signer.publicKeyLine() + "\u0000").toByteArray())`, then return `AdbConnectResult.AwaitingAuthorization` if no `A_CNXN` arrives before `soTimeoutMs` elapses.
  - `A_CNXN` → return `AdbConnectResult.Connected`.
- `ConnectException` or `SocketTimeoutException` on the initial connect → `Unreachable`. Any other `IOException` → `Failed`.
- Expose `internal fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray)` and `internal fun read(): Pair<AdbHeader, ByteArray>` for `AdbStream`.
- `close()` closes the socket, swallowing `IOException`.

Document why a second TOKEN is treated as "unknown key": adbd cannot distinguish "bad signature" from "key we have never seen" for the client's benefit, and both are resolved the same way — offer the public key and let the user authorize.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbConnectionTest*"`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbConnection.kt \
        app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/FakeAdbDaemon.kt \
        app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbConnectionTest.kt
git commit -m "Add the adb connection handshake and a protocol-speaking fake daemon"
```

---

### Task 5: Shell streams with flow control and a payload cap

**Files:**
- Create: `app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbStream.kt`
- Test: `app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbStreamTest.kt`

**Interfaces:**
- Consumes: `AdbConnection.send` / `AdbConnection.read` (Task 4), `AdbMessage` (Task 1).
- Produces: `suspend fun AdbConnection.shell(command: String, maxBytes: Int = MAX_DUMP_BYTES): String?`; `const val MAX_DUMP_BYTES = 4 * 1024 * 1024`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged.adb

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbStreamTest {

    private var daemon: FakeAdbDaemon? = null

    @After fun tearDown() { daemon?.stop() }

    private suspend fun connected(responses: Map<String, ByteArray>, chunk: Int = 8): AdbConnection {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(line, responses, writeChunkSize = chunk)
            .also { daemon = it; it.start() }
        return AdbConnection("127.0.0.1", fake.port, signer, soTimeoutMs = 2_000)
            .also { it.connect() }
    }

    @Test
    fun readsAChunkedDumpCompletely() = runTest {
        val body = "Current Battery Service state:\n  level: 84\n  temperature: 298\n"
        val connection = connected(mapOf("shell:dumpsys battery" to body.toByteArray()), chunk = 8)

        assertEquals(body, connection.shell("dumpsys battery"))
    }

    @Test
    fun acknowledgesEveryWriteSoTheDaemonNeverStalls() = runTest {
        // 200 bytes at 8 per chunk is 25 WRTEs. A client that acks only the first hangs
        // here, which is why the daemon requires the ack rather than the test counting
        // them after the fact.
        val body = "x".repeat(200)
        val connection = connected(mapOf("shell:dumpsys battery" to body.toByteArray()), chunk = 8)

        assertEquals(body, connection.shell("dumpsys battery"))
        assertTrue(daemon!!.acks.size >= 25)
        assertTrue(daemon!!.acks.all { it == A_OKAY })
    }

    @Test
    fun returnsNullWhenThePayloadExceedsTheCap() = runTest {
        val body = ByteArray(4096) { 'y'.code.toByte() }
        val connection = connected(mapOf("shell:dumpsys battery" to body))

        assertNull(connection.shell("dumpsys battery", maxBytes = 1024))
    }

    @Test
    fun returnsNullForAnEmptyResponse() = runTest {
        val connection = connected(mapOf("shell:dumpsys battery" to ByteArray(0)))

        assertNull(connection.shell("dumpsys battery"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbStreamTest*"`
Expected: FAIL — unresolved reference `shell`.

- [ ] **Step 3: Implement**

`shell(command, maxBytes)`:

- Allocate a local stream id from an `AtomicInteger` starting at 1.
- Send `A_OPEN(localId, 0, "shell:$command\u0000".toByteArray())`.
- Loop reading messages:
  - `A_OKAY` → record the remote id, continue.
  - `A_WRTE` → append the payload to a `ByteArrayOutputStream`, then **immediately** send `A_OKAY(localId, remoteId)`, then compare the accumulated size against `maxBytes` and return `null` if it is exceeded.
  - `A_CLSE` → break.
- Return the buffer decoded as a `String`, or `null` if blank.
- Any `IOException` or `SocketTimeoutException` → `null`.

The ack must be sent **before** the size check, so that an oversize abort does not itself stall the daemon mid-teardown.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbStreamTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbStream.kt \
        app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbStreamTest.kt
git commit -m "Add adb shell streams with flow control and a payload cap"
```

---

### Task 6: `PrivilegedAvailability` and its reducer

Implemented before the seam so that `TransportState` exists when Task 7 refers to it.

**Files:**
- Create: `app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/PrivilegedAvailability.kt`
- Test: `app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/PrivilegedAvailabilityTest.kt`

**Interfaces:**
- Produces: `enum class Transport { Root, Adb }`; `sealed interface TransportState`; `sealed interface PrivilegedAvailability`; `fun privilegedAvailability(root: TransportState, adb: TransportState): PrivilegedAvailability`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegedAvailabilityTest {

    @Test
    fun rootWinsWhenBothTransportsAreReady() {
        // Root survives a reboot with no setup and adb does not, so a user with both
        // belongs on the one that still works tomorrow.
        assertEquals(
            PrivilegedAvailability.Ready(Transport.Root),
            privilegedAvailability(root = TransportState.Ready, adb = TransportState.Ready),
        )
    }

    @Test
    fun adbIsUsedWhenRootIsUnavailable() {
        assertEquals(
            PrivilegedAvailability.Ready(Transport.Adb),
            privilegedAvailability(root = TransportState.Unavailable, adb = TransportState.Ready),
        )
    }

    @Test
    fun aReadyTransportOutranksAPendingOne() {
        assertEquals(
            PrivilegedAvailability.Ready(Transport.Adb),
            privilegedAvailability(
                root = TransportState.AwaitingAuthorization,
                adb = TransportState.Ready,
            ),
        )
    }

    @Test
    fun awaitingAuthorizationOutranksDeniedBecauseAPromptIsOnScreen() {
        assertEquals(
            PrivilegedAvailability.AwaitingAuthorization,
            privilegedAvailability(
                root = TransportState.Denied,
                adb = TransportState.AwaitingAuthorization,
            ),
        )
    }

    @Test
    fun connectingOutranksDenied() {
        assertEquals(
            PrivilegedAvailability.Connecting,
            privilegedAvailability(root = TransportState.Denied, adb = TransportState.Connecting),
        )
    }

    @Test
    fun deniedOutranksUnavailableSoTheUserIsToldWhyRatherThanNothing() {
        assertEquals(
            PrivilegedAvailability.Denied,
            privilegedAvailability(root = TransportState.Denied, adb = TransportState.Unavailable),
        )
    }

    @Test
    fun unavailableWhenNeitherTransportOffersAnything() {
        assertEquals(
            PrivilegedAvailability.Unavailable,
            privilegedAvailability(
                root = TransportState.Unavailable,
                adb = TransportState.Unavailable,
            ),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testPlayDebugUnitTest --tests "*PrivilegedAvailabilityTest*"`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged

/**
 * Which privileged transport a reading actually came through. Surfaced because the two
 * have different reboot stories the user needs told: root survives one, adb does not.
 */
enum class Transport { Root, Adb }

sealed interface TransportState {
    data object Unavailable : TransportState
    data object AwaitingAuthorization : TransportState
    data object Denied : TransportState
    data object Connecting : TransportState
    data object Ready : TransportState
}

sealed interface PrivilegedAvailability {
    data object Unavailable : PrivilegedAvailability
    data object AwaitingAuthorization : PrivilegedAvailability
    data object Denied : PrivilegedAvailability
    data object Connecting : PrivilegedAvailability
    data class Ready(val via: Transport) : PrivilegedAvailability
}

/**
 * Reduces two independently-observed transports into the single state the UI renders.
 *
 * Kept pure and separate from `AdbGateway` -- which is the impure part, holding sockets
 * and processes -- so this precedence is JVM-testable without a device, an emulator or
 * Robolectric, exactly as `shizukuAvailability` was before it.
 *
 * Precedence is by what the user can act on, most actionable first: a working transport
 * beats a pending one; a prompt currently on screen beats a refusal already given; a
 * refusal the user can still reverse beats nothing at all. Root outranks adb at equal
 * rank because it needs no per-boot setup.
 */
fun privilegedAvailability(
    root: TransportState,
    adb: TransportState,
): PrivilegedAvailability = when {
    root == TransportState.Ready -> PrivilegedAvailability.Ready(Transport.Root)
    adb == TransportState.Ready -> PrivilegedAvailability.Ready(Transport.Adb)
    root == TransportState.AwaitingAuthorization || adb == TransportState.AwaitingAuthorization ->
        PrivilegedAvailability.AwaitingAuthorization
    root == TransportState.Connecting || adb == TransportState.Connecting ->
        PrivilegedAvailability.Connecting
    root == TransportState.Denied || adb == TransportState.Denied ->
        PrivilegedAvailability.Denied
    else -> PrivilegedAvailability.Unavailable
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testPlayDebugUnitTest --tests "*PrivilegedAvailabilityTest*"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/PrivilegedAvailability.kt \
        app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/PrivilegedAvailabilityTest.kt
git commit -m "Add the two-transport privileged availability reducer"
```

---

### Task 7: The `PrivilegedShell` seam and both transports

**Files:**
- Create: `app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/PrivilegedShell.kt`
- Create: `app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbShell.kt`
- Create: `app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/RootShell.kt`
- Test: `app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbShellTest.kt`

**Interfaces:**
- Consumes: `AdbConnection`, `shell()` (Tasks 4–5); `TransportState` (Task 6).
- Produces:

```kotlin
const val CMD_DUMP_BATTERY = "dumpsys battery"
const val CMD_DUMP_CHECKIN = "dumpsys batterystats --checkin"
const val LOOPBACK_HOST = "127.0.0.1"

interface PrivilegedShell {
    val state: StateFlow<TransportState>
    suspend fun runDump(): String?
    suspend fun runCheckin(): String?
    suspend fun connect()
    fun refresh()
}
```

**The allowlist is this interface.** There is no method that takes a command. `CMD_DUMP_BATTERY` and `CMD_DUMP_CHECKIN` are the only strings ever handed to `shell()`. Adding a third is a design decision, not an implementation detail.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged.adb

import com.mralaminahamed.batteryhealth.data.privileged.CMD_DUMP_BATTERY
import com.mralaminahamed.batteryhealth.data.privileged.CMD_DUMP_CHECKIN
import com.mralaminahamed.batteryhealth.data.privileged.TransportState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbShellTest {

    private var daemon: FakeAdbDaemon? = null

    @After fun tearDown() { daemon?.stop() }

    @Test
    fun runsBothAllowlistedCommands() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(
            knownPublicKeyLine = line,
            shellResponses = mapOf(
                "shell:$CMD_DUMP_BATTERY" to "level: 84\n".toByteArray(),
                "shell:$CMD_DUMP_CHECKIN" to "9,0,i,vers,36\n".toByteArray(),
            ),
        ).also { daemon = it; it.start() }

        val shell = AdbShell(port = fake.port, signer = signer)
        shell.connect()

        assertEquals(TransportState.Ready, shell.state.value)
        assertEquals("level: 84\n", shell.runDump())
        assertEquals("9,0,i,vers,36\n", shell.runCheckin())
    }

    @Test
    fun isUnavailableAndReturnsNullWhenNothingIsListening() = runTest {
        val closed = FakeAdbDaemon().also { it.start() }
        val port = closed.port
        closed.stop()

        val shell = AdbShell(port = port, signer = FakeAdbDaemon.signer().first)
        shell.connect()

        assertEquals(TransportState.Unavailable, shell.state.value)
        assertNull(shell.runDump())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbShellTest*"`
Expected: FAIL — unresolved reference `AdbShell`.

- [ ] **Step 3: Implement all three files**

`PrivilegedShell.kt` — the interface above plus the three constants. `LOOPBACK_HOST` lives here because Task 12's enforcement test keys off it.

`AdbShell.kt` — holds an `AdbConnection` built against `LOOPBACK_HOST` and the injected port. Map `AdbConnectResult` to `TransportState`: `Connected` → `Ready`, `AwaitingAuthorization` → `AwaitingAuthorization`, `Unreachable` → `Unavailable`, `Failed` → `Unavailable`. `runDump()` and `runCheckin()` call `connection.shell(CMD_…)`; when either returns `null` from a connection that had been `Ready`, flip `state` to `Unavailable` — that is the live-degrade path the spec requires. Guard `connect()` with an `AtomicBoolean` so one is in flight at a time.

`RootShell.kt` — `exec(arrayOf("su", "-c", CMD_…))` on `Dispatchers.IO`, read stdout fully, `destroy()` after the transport-side timeout (3s for the dump, 8s for the checkin), `null` on non-zero exit or any exception. `connect()` runs `su -c id` as the probe; while it is outstanding `state` is `AwaitingAuthorization`, because that is exactly when Magisk's dialog is up. **Never call `connect()` from `init`** — Task 8's gateway decides when.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbShellTest*"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/PrivilegedShell.kt \
        app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbShell.kt \
        app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/RootShell.kt \
        app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/adb/AdbShellTest.kt
git commit -m "Add the privileged shell seam with adb and root transports"
```

---

### Task 8: ADB port and root-grant preferences

**Files:**
- Modify: `app/src/main/java/com/mralaminahamed/batteryhealth/data/settings/SettingsStore.kt`
- Test: `app/src/androidTest/java/com/mralaminahamed/batteryhealth/data/settings/SettingsStoreTest.kt`

**Interfaces:**
- Produces: `val adbPort: Flow<Int>` (default `5555`); `suspend fun setAdbPort(port: Int)`; `val rootPreviouslyGranted: Flow<Boolean>` (default `false`); `suspend fun setRootPreviouslyGranted(granted: Boolean)`.

- [ ] **Step 1: Read the existing file first**

Run: `sed -n '1,200p' app/src/main/java/com/mralaminahamed/batteryhealth/data/settings/SettingsStore.kt`

Follow the key-declaration and flow-mapping pattern already there. Do not introduce a second style alongside it.

- [ ] **Step 2: Write the failing tests**

Add to the existing `SettingsStoreTest`, matching its existing setup and `store` fixture:

```kotlin
@Test
fun adbPortDefaultsTo5555() = runTest {
    assertEquals(5555, store.adbPort.first())
}

@Test
fun adbPortRoundTrips() = runTest {
    store.setAdbPort(5037)
    assertEquals(5037, store.adbPort.first())
}

@Test
fun rootPreviouslyGrantedDefaultsToFalse() = runTest {
    // Load-bearing default: true here would make the app probe su on first launch and
    // raise Magisk's dialog before the user has asked for anything.
    assertEquals(false, store.rootPreviouslyGranted.first())
}

@Test
fun rootPreviouslyGrantedRoundTrips() = runTest {
    store.setRootPreviouslyGranted(true)
    assertEquals(true, store.rootPreviouslyGranted.first())
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew connectedPlayDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mralaminahamed.batteryhealth.data.settings.SettingsStoreTest
```
Expected: FAIL — unresolved reference `adbPort`.

- [ ] **Step 4: Implement and re-run**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mralaminahamed/batteryhealth/data/settings/SettingsStore.kt \
        app/src/androidTest/java/com/mralaminahamed/batteryhealth/data/settings/SettingsStoreTest.kt
git commit -m "Add adb port and root grant preferences"
```

---

### Task 9: Cutover — `AdbGateway` replaces Shizuku

One task, because it is atomic: the build does not compile between the interface change and the deletion, and a reviewer cannot sensibly accept half of it.

**Files:**
- Create: `app/src/main/java/com/mralaminahamed/batteryhealth/data/privileged/AdbGateway.kt`
- Test: `app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/AdbGatewayTest.kt`
- Modify: `data/privileged/PrivilegedBatterySource.kt`, `di/PrivilegedModule.kt`, `data/repo/BatteryRepository.kt`, `ui/health/HealthViewModel.kt`, `ui/apps/AppsViewModel.kt`, `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/src/main/keepRules/rules.keep`
- Delete: `data/privileged/ShizukuGateway.kt`, `data/privileged/ShizukuAvailability.kt`, `data/privileged/PrivilegedBatteryService.kt`, `app/src/main/aidl/com/mralaminahamed/batteryhealth/data/privileged/IUserService.aidl`, `app/src/test/java/.../ShizukuAvailabilityTest.kt`
- Rename: `ShizukuGatewayTimeoutTest.kt` → `AdbGatewayTimeoutTest.kt`, `ShizukuGatewayCheckinTimeoutTest.kt` → `AdbGatewayCheckinTimeoutTest.kt` (class name and `Shizuku` → `Adb` references only; the timeout functions under test do not change)

**Interfaces:**
- Consumes: `PrivilegedShell`, `AdbShell`, `RootShell` (Task 7); `privilegedAvailability` (Task 6); `SettingsStore.adbPort`, `SettingsStore.rootPreviouslyGranted` (Task 8).
- Produces: `@Singleton class AdbGateway : PrivilegedBatterySource`.

**Interface diff on `PrivilegedBatterySource`:**

| Member | Change |
|---|---|
| `state` | `StateFlow<ShizukuAvailability>` → `StateFlow<PrivilegedAvailability>` |
| `requestPermission()` | → `suspend fun connect()` |
| `dumpBattery()`, `dumpBatteryStatsCheckin()`, `refresh()` | unchanged |

Rewrite its KDoc: it currently explains Shizuku's global static API, which will no longer exist.

- [ ] **Step 1: Write the failing gateway test**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbGatewayTest {

    private class FakeShell(
        initial: TransportState = TransportState.Unavailable,
        private val dump: String? = null,
        private val checkin: String? = null,
    ) : PrivilegedShell {
        val flow = MutableStateFlow(initial)
        override val state = flow
        var connectCalls = 0
            private set

        override suspend fun runDump() = dump
        override suspend fun runCheckin() = checkin
        override suspend fun connect() { connectCalls++ }
        override fun refresh() = Unit
    }

    @Test
    fun routesDumpsToWhicheverTransportIsReady() = runTest {
        val adb = FakeShell(TransportState.Ready, dump = "level: 84\n")
        val gateway = AdbGateway(FakeShell(), adb)

        assertEquals(PrivilegedAvailability.Ready(Transport.Adb), gateway.state.value)
        assertEquals("level: 84\n", gateway.dumpBattery())
    }

    @Test
    fun prefersRootWhenBothAreReady() = runTest {
        val root = FakeShell(TransportState.Ready, dump = "from root")
        val adb = FakeShell(TransportState.Ready, dump = "from adb")

        assertEquals("from root", AdbGateway(root, adb).dumpBattery())
    }

    @Test
    fun returnsNullWhenNoTransportIsReady() = runTest {
        val gateway = AdbGateway(FakeShell(), FakeShell())

        assertNull(gateway.dumpBattery())
        assertNull(gateway.dumpBatteryStatsCheckin())
    }

    @Test
    fun degradesLiveWhenAReadyTransportDrops() = runTest {
        // The property the Shizuku gateway had and must not lose: a transport dying
        // mid-session reaches state with no exception anywhere downstream.
        val adb = FakeShell(TransportState.Ready, dump = "level: 84\n")
        val gateway = AdbGateway(FakeShell(), adb)
        assertEquals(PrivilegedAvailability.Ready(Transport.Adb), gateway.state.value)

        adb.flow.value = TransportState.Unavailable

        assertEquals(PrivilegedAvailability.Unavailable, gateway.state.value)
        assertNull(gateway.dumpBattery())
    }

    @Test
    fun neverProbesRootBeforeConnectIsCalled() = runTest {
        val root = FakeShell()
        AdbGateway(root, FakeShell())

        // Constructing the gateway must not raise Magisk's dialog.
        assertEquals(0, root.connectCalls)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testPlayDebugUnitTest --tests "*AdbGatewayTest*"`
Expected: FAIL — unresolved reference `AdbGateway`.

- [ ] **Step 3: Implement `AdbGateway`**

- `@Singleton`. A primary constructor taking two `PrivilegedShell`s keeps the test above simple; an `@Inject` constructor taking `RootShell`, `AdbShell` and `SettingsStore` delegates to it.
- `state` = `combine(root.state, adb.state) { r, a -> privilegedAvailability(r, a) }.stateIn(scope, SharingStarted.Eagerly, initial)` — the same shape `ShizukuGateway.state` uses today.
- `dumpBattery()` / `dumpBatteryStatsCheckin()` route to whichever transport `state.value` names, wrapped in the **unchanged** `withGatewayDumpTimeout` / `withGatewayCheckinTimeout`. Move those two functions and their four KDoc blocks out of the deleted `ShizukuGateway.kt` into `AdbGateway.kt` **verbatim** — the rationale still holds and re-deriving it loses information.
- `connect()` calls `adb.connect()`, and calls `root.connect()` **only** when `settingsStore.rootPreviouslyGranted.first()` is true or the caller explicitly asked for root.
- `refresh()` calls `refresh()` on both. No hot loop, no scheduled retry.
- Process-lifetime `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, as today.

- [ ] **Step 4: Apply the rest of the cutover**

1. Edit `PrivilegedBatterySource.kt` per the interface diff, KDoc rewritten.
2. `PrivilegedModule.kt`: `fun providePrivilegedBatterySource(gateway: AdbGateway): PrivilegedBatterySource = gateway`.
3. Update `data/repo/BatteryRepository.kt`, `ui/health/HealthViewModel.kt`, `ui/apps/AppsViewModel.kt` for the renamed type. `requestPermission()` becomes `connect()`, now `suspend` — call it from the ViewModel scope already present.
4. `AndroidManifest.xml` — delete the `moe.shizuku.manager.permission.API_V23` `uses-permission`, the `<queries>` block naming `moe.shizuku.privileged.api`, and the `rikka.shizuku.ShizukuProvider` `<provider>`. Add:

```xml
<!-- Loopback only: the privileged tier reaches the on-device adb daemon on 127.0.0.1.
     Android enforces INTERNET at socket creation via the inet group, and loopback is
     not exempt, so staying on-device does not avoid it. The app makes no outbound
     network requests; PrivilegedShellLoopbackTest asserts that mechanically. -->
<uses-permission android:name="android.permission.INTERNET" />
```

5. `app/build.gradle.kts` — remove `implementation(libs.shizuku.api)` and `implementation(libs.shizuku.provider)`. Remove `aidl = true` from `buildFeatures` **together with its comment**, which names `IUserService.aidl` and becomes false.
6. `gradle/libs.versions.toml` — remove `shizuku` from `[versions]` and `shizuku-api` / `shizuku-provider` from `[libraries]`.
7. Delete the four Shizuku source files and `ShizukuAvailabilityTest.kt`; rename the two timeout tests.
8. `app/src/main/keepRules/rules.keep` — remove the Shizuku reflection keeps. Nothing in approach A is reflected, so this may end up empty; confirm in Step 6 rather than assuming.

- [ ] **Step 5: Verify nothing references Shizuku any more**

```bash
grep -rn "hizuku\|rikka\|IUserService" app/src gradle/libs.versions.toml app/build.gradle.kts || echo "clean"
```
Expected: `clean`. Any hit is a missed rename — fix before continuing.

- [ ] **Step 6: Run the full suite and a release build**

```bash
./gradlew testPlayDebugUnitTest
./gradlew assemblePlayRelease
```
Expected: unit tests PASS; the release build succeeds with R8 enabled. If R8 strips something, that is what `rules.keep` is for — add the minimal keep and a comment saying why.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Replace Shizuku with the in-app adb and root transports"
```

---

### Task 10: Rename `NeedsShizuku` to `NeedsPrivilegedAccess`

Mechanical and wide. Kept out of Task 9 so the cutover's diff stays reviewable.

**Files:** `domain/Reading.kt`, `domain/BatteryModels.kt`, `data/repo/CycleCountResolver.kt`, `data/framework/BatteryProperty.kt`, `ui/components/ReadingSlot.kt`, `ui/health/HealthUiState.kt`, `ui/apps/AppsUiState.kt`, `ui/apps/AppsScreen.kt`, `play/.../PlayAppLabelResolver.kt`, plus `test/domain/ReadingTest.kt`, `test/domain/BatteryModelsTest.kt`, `test/data/repo/CycleCountResolverTest.kt`, `test/ui/health/HealthUiStateTest.kt`, `androidTest/.../ReadingSlotTest.kt`, `androidTest/.../AppsScreenTest.kt`.

- [ ] **Step 1: Rename the declaration and fix its KDoc**

In `domain/Reading.kt`:

```kotlin
    /** The privileged tier would provide it, but no transport is connected. */
    data object NeedsPrivilegedAccess : Reading<Nothing>
```

and in `map`:

```kotlin
    Reading.NeedsPrivilegedAccess -> Reading.NeedsPrivilegedAccess
```

- [ ] **Step 2: Sweep the rest**

```bash
grep -rl "NeedsShizuku" app/src | xargs sed -i 's/NeedsShizuku/NeedsPrivilegedAccess/g'
grep -rn "NeedsShizuku" app/src || echo "clean"
```
Expected: `clean`.

- [ ] **Step 3: Fix user-facing prose the sweep did not catch**

```bash
grep -rn "Shizuku" app/src/main/res/values/strings.xml app/src/main/java || echo "no prose left"
```
Any remaining prose naming Shizuku is now false. Rewrite it to describe the privileged tier, not a product the app no longer uses.

- [ ] **Step 4: Run the suite**

```bash
./gradlew testPlayDebugUnitTest
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Rename NeedsShizuku to NeedsPrivilegedAccess"
```

---

### Task 11: `UnlockCard` and screen copy for the new states

**Files:**
- Modify: `app/src/main/java/com/mralaminahamed/batteryhealth/ui/components/UnlockCard.kt`
- Modify: `app/src/main/java/com/mralaminahamed/batteryhealth/ui/health/HealthScreen.kt`
- Test: `app/src/androidTest/java/com/mralaminahamed/batteryhealth/ui/components/UnlockCardTest.kt`

**Interfaces:**
- Consumes: `PrivilegedAvailability`, `Transport` (Task 6).
- Produces: `UnlockCard(availability: PrivilegedAvailability, dumpFailed: Boolean, onConnect: () -> Unit, onLearnMore: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier)`.

`onOpenShizuku` and `onRequestPermission` are gone: there is no second app to open and no Shizuku permission to request. `onConnect` replaces both.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun rendersNothingOnceReadyAndTheLastDumpSucceeded() {
    composeRule.setContent {
        UnlockCard(PrivilegedAvailability.Ready(Transport.Adb), dumpFailed = false, {}, {}, {})
    }
    composeRule.onNodeWithTag(UnlockCardTags.ROOT).assertDoesNotExist()
}

@Test
fun explainsTheOneTimeAdbStepWhenNoTransportIsAvailable() {
    composeRule.setContent {
        UnlockCard(PrivilegedAvailability.Unavailable, dumpFailed = false, {}, {}, {})
    }
    composeRule.onNodeWithTag(UnlockCardTags.ROOT).assertExists()
    composeRule.onNodeWithTag(UnlockCardTags.ACTION).assertTextEquals("How to enable")
}

@Test
fun tellsTheUserToLookAtTheirScreenWhileAPromptIsUp() {
    composeRule.setContent {
        UnlockCard(PrivilegedAvailability.AwaitingAuthorization, dumpFailed = false, {}, {}, {})
    }
    // No button: the action is on the system dialog, not in this card. A button here
    // would compete with the prompt the user is supposed to be answering.
    composeRule.onNodeWithTag(UnlockCardTags.ACTION).assertDoesNotExist()
}

@Test
fun offersTryAgainAfterARefusal() {
    composeRule.setContent {
        UnlockCard(PrivilegedAvailability.Denied, dumpFailed = false, {}, {}, {})
    }
    composeRule.onNodeWithTag(UnlockCardTags.ACTION).assertTextEquals("Try again")
}

@Test
fun offersRetryWhenReadyButTheLastDumpCameBackEmpty() {
    composeRule.setContent {
        UnlockCard(PrivilegedAvailability.Ready(Transport.Root), dumpFailed = true, {}, {}, {})
    }
    composeRule.onNodeWithTag(UnlockCardTags.ACTION).assertTextEquals("Retry")
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
./gradlew connectedPlayDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mralaminahamed.batteryhealth.ui.components.UnlockCardTest
```

- [ ] **Step 3: Rewrite the copy**

Keep the existing structure — one `when` producing the explanation, one producing the label/handler pair, so the two can never describe different states. Replace the strings:

```kotlin
private fun explanation(availability: PrivilegedAvailability, dumpFailed: Boolean): String =
    when (availability) {
        PrivilegedAvailability.Unavailable ->
            "State of health, first-use date and Battery Protect status sit behind a " +
                "permission this app cannot request on its own. Connect it once from a " +
                "computer with \"adb tcpip 5555\" and this app takes it from there. " +
                "You'll need to repeat that after a restart. A rooted device skips the step."
        PrivilegedAvailability.AwaitingAuthorization ->
            "Check your screen -- your device is asking whether to allow this. " +
                "Approve it and the readings appear."
        PrivilegedAvailability.Denied ->
            "Access was declined, so the privileged readings stay hidden. Nothing else " +
                "in the app is affected, and you can try again whenever you like."
        PrivilegedAvailability.Connecting -> "Connecting..."
        is PrivilegedAvailability.Ready ->
            if (dumpFailed) {
                "Connected, but the last privileged read didn't come back -- most likely " +
                    "a dropped shell call. Retrying costs nothing and often just works."
            } else {
                "" // unreachable; UnlockCard returns before rendering this case
            }
    }
```

Actions: `Unavailable` → `"How to enable"` to `onLearnMore`; `Denied` → `"Try again"` to `onConnect`; `AwaitingAuthorization` and `Connecting` → `null`; `Ready` → `"Retry"` to `onRetry` when `dumpFailed`, else `null`.

Update the class KDoc: it currently claims "four distinct not-yet-bound states" and names Shizuku throughout. Both are now wrong.

- [ ] **Step 4: Update `HealthScreen.kt`'s call site**

Adjust for the new parameter list, then re-run the test command from Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Rewrite the unlock card for the adb and root transports"
```

---

### Task 12: Enforce loopback-only networking

The `INTERNET` permission is new and users will see it in the store listing. This makes "we only talk to 127.0.0.1" checkable rather than merely claimed.

**Files:**
- Test: `app/src/test/java/com/mralaminahamed/batteryhealth/data/privileged/PrivilegedShellLoopbackTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.mralaminahamed.batteryhealth.data.privileged

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app declares INTERNET solely to reach adbd on 127.0.0.1. That claim is made to
 * users in the manifest comment, the README and the store listing, so it is worth
 * enforcing rather than trusting: this fails if any source file constructs a socket
 * without going through [LOOPBACK_HOST].
 */
class PrivilegedShellLoopbackTest {

    @Test
    fun noSourceFileNamesANonLoopbackSocketHost() {
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("Socket(") && !text.contains("LOOPBACK_HOST")
            }
            .map { it.path }
            .toList()

        assertEquals(emptyList<String>(), offenders)
    }
}
```

If `AdbShell` still passes a `"127.0.0.1"` literal rather than `LOOPBACK_HOST`, change it now.

- [ ] **Step 2: Run**

```bash
./gradlew testPlayDebugUnitTest --tests "*PrivilegedShellLoopbackTest*"
```
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "Assert the privileged transport only ever dials loopback"
```

---

### Task 13: Device verification on real hardware

Nothing here is automatable: instrumented tests run *over* adb and must never restart adbd, or they cut their own transport out from under themselves.

**Device:** Samsung Galaxy A35 5G (SM-A356E), Android 16, per the README's "Verified on".

- [ ] **Step 1: Install and confirm the unprivileged tier is unaffected**

```bash
./gradlew installPlayDebug
adb shell am start -n com.mralaminahamed.batteryhealth/.MainActivity
```
Expected: Live, History and Health all render. Privileged rows read "needs privileged access". `UnlockCard` shows the "How to enable" copy.

- [ ] **Step 2: Enable the transport and connect**

```bash
adb tcpip 5555
```
Then trigger connect in the app. Expected: the device raises "Allow USB debugging?"; `UnlockCard` shows the awaiting-authorization copy with no button; after approval the card disappears and state of health, cycle count and Battery Protect populate.

- [ ] **Step 3: Confirm the dump actually parses**

```bash
adb shell dumpsys battery
```
Expected: level, temperature and voltage agree with what the app displays.

- [ ] **Step 4: Confirm live degradation**

```bash
adb usb
```
Expected: within one refresh the privileged rows return to "needs privileged access", `UnlockCard` reappears, **no crash**, and the unprivileged rows keep updating.

- [ ] **Step 5: Reboot and confirm honest messaging**

```bash
adb reboot
```
After boot, open the app without re-running `adb tcpip`. Expected: `Unavailable`, with copy telling the user the step must be repeated.

- [ ] **Step 6: Root path**

If a rooted device is available, repeat Steps 2–4 through root and confirm `Ready(Root)` is preferred. **If no rooted device is available, record it as unverified** in the commit message and in the README. Do not claim it works.

- [ ] **Step 7: Record results**

```bash
git commit --allow-empty -F - <<'MSG'
Verify the adb privileged tier on SM-A356E

Steps 1-5 pass on Android 16: connect, authorize, parse, live-degrade on
`adb usb`, and honest Unavailable copy after reboot.

Root path: <verified on ... | unverified, no rooted device available>.
MSG
```

---

## Self-review

**Spec coverage.** §1 → Tasks 1–5. §2 → Tasks 6, 9. §3 → Tasks 7, 9. §4 → Tasks 9–12. §5 → tests inside every task plus Task 13. Every risk in the spec's risk table has a task that addresses it: Keystore `NONEwithRSA` → Task 3 Step 4 (with an explicit stop-and-report); `n0inv`/`rr` arithmetic → Task 2; unacked `A_WRTE` → Task 5; `INTERNET` perception → Task 12; adbd gone after reboot → Task 13 Step 5; root probe raising a dialog unprompted → Task 9's `neverProbesRootBeforeConnectIsCalled`.

**Ordering.** Task 6 (`TransportState`) precedes Task 7, which consumes it, so the build stays green at every commit. Tasks 1–8 are purely additive; Task 9 is the single non-compiling window, which is why it is one task.

**Naming consistency.** `runDump`/`runCheckin` are identical in Tasks 7 and 9. `AdbSigner` is introduced in Task 3 and reused in Tasks 4, 5, 7. `LOOPBACK_HOST` is introduced in Task 7 and enforced in Task 12. `AdbConnectResult` is produced in Task 4 and mapped in Task 7.

**Deliberately deferred.** README truth-up, R8 verification, the Play `specialUse` foreground-service declaration, store listing copy, and widened `dumpsys` metrics are SP4, not this plan.
