package com.mralaminahamed.batteryhealth.data.privileged

import java.io.File
import java.util.Scanner
import org.junit.Assert.fail
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
        // Gradle's unit-test working directory is the app module root. Resolve the source
        // root as src/main/java from there. We walk from an explicit absolute File to
        // avoid silent failure if the working directory is not where we expect.
        val sourceRoot = File("src/main/java")
        if (!sourceRoot.exists()) {
            fail("Source directory does not exist at ${sourceRoot.absolutePath}")
        }

        // Recursively walk the source tree and collect all .kt files.
        val kotlinFiles = mutableListOf<File>()
        fun walkAndCollect(dir: File) {
            val contents = dir.listFiles() ?: return
            for (file in contents) {
                if (file.isDirectory) {
                    walkAndCollect(file)
                } else if (file.isFile && file.name.endsWith(".kt")) {
                    kotlinFiles.add(file)
                }
            }
        }
        walkAndCollect(sourceRoot)

        // Guard: assert that we actually scanned something. A silent empty scan is not a pass.
        if (kotlinFiles.isEmpty()) {
            fail("Scanned zero .kt files in ${sourceRoot.absolutePath}; source tree is empty or path resolution failed")
        }

        // Guard: assert that the file we expect to scan is among them. If AdbConnection.kt
        // has been renamed or moved, this test should notice rather than silently passing
        // with no Socket()-bearing files in the tree.
        var adbConnectionFile: File? = null
        for (file in kotlinFiles) {
            if (file.name == "AdbConnection.kt") {
                adbConnectionFile = file
                break
            }
        }
        if (adbConnectionFile == null) {
            fail("AdbConnection.kt was not found among ${kotlinFiles.size} scanned .kt files")
        }

        val adbConnectionText = Scanner(adbConnectionFile).use { it.useDelimiter("\\A").next() }
        if (!adbConnectionText.contains("Socket(")) {
            fail("AdbConnection.kt does not contain Socket(; the test target file has changed")
        }
        if (!adbConnectionText.contains("LOOPBACK_HOST")) {
            fail("AdbConnection.kt contains Socket( but not LOOPBACK_HOST; the constraint is violated")
        }

        // Now scan for offenders: files that name a Socket( but don't mention LOOPBACK_HOST.
        val offenderPaths = mutableListOf<String>()
        for (file in kotlinFiles) {
            val text = Scanner(file).use { it.useDelimiter("\\A").next() }
            if (text.contains("Socket(") && !text.contains("LOOPBACK_HOST")) {
                offenderPaths.add(file.path)
            }
        }

        if (offenderPaths.isNotEmpty()) {
            fail("Found files with Socket( but no LOOPBACK_HOST: $offenderPaths")
        }
    }
}
