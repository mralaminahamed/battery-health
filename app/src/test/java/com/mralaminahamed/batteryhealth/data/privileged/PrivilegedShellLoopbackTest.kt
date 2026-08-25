package com.mralaminahamed.batteryhealth.data.privileged

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

/**
 * The app declares INTERNET solely to reach adbd on 127.0.0.1. That claim is made to
 * users in the manifest comment, the README and the store listing, so it is worth
 * enforcing structurally rather than trusting:
 *
 *  - Exactly one production file may construct a [java.net.Socket] -- `AdbConnection.kt` --
 *    and every production source set (each `src/<name>/java` directory except `test` and
 *    `androidTest`) is scanned to find it, so a shipped flavor source file can't dial out
 *    unnoticed.
 *  - Inside that one file, every socket construction must be the bare, argument-less
 *    `Socket()` form. `AdbConnection` itself has no host constructor parameter any more --
 *    [com.mralaminahamed.batteryhealth.data.privileged.adb.LOOPBACK_HOST] is wired in
 *    directly at the `connect()` call site -- so a `Socket("host", ...)` appearing here can
 *    only mean the invariant has been broken.
 *  - No production source may reach for another outbound-network surface
 *    ([java.nio.channels.SocketChannel], `HttpURLConnection`, `openConnection`, `URL(`)
 *    instead.
 *
 * Comments and KDoc are stripped before any of the above is matched, so prose describing
 * this very enforcement can't trip or mask a rule.
 */
class PrivilegedShellLoopbackTest {

    /** Source-set directory names that are never shipped and must not be scanned. */
    private val nonProductionSourceSets = setOf("test", "androidTest")

    /**
     * A `Socket(` not immediately preceded by an identifier character, so it matches a bare
     * construction (`Socket(...)`) but not an unrelated identifier ending in "Socket", e.g.
     * `DatagramSocket(` or `InetSocketAddress(` (which doesn't contain "Socket(" at all, but
     * the guard costs nothing and documents the intent).
     */
    private val socketConstruction = Regex("(?<![A-Za-z0-9_])Socket\\(")

    private val otherEgressTokens =
        listOf("SocketChannel", "HttpURLConnection", "openConnection", "URL(")

    /** Full `/* ... */` and `/** ... */` spans, comment markers included. */
    private val blockComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    /**
     * Discovers every production `.kt` file, derived from the module's actual source-set
     * layout rather than a hardcoded list: any `src/<name>/java` directory except `test`
     * and `androidTest`. A flavor added later is picked up automatically. Every guard below
     * fails loudly (via [fail], which throws) rather than letting a broken discovery step
     * pass silently on zero files.
     */
    private fun productionKotlinFiles(): List<File> {
        val srcRoot = File("src")
        if (!srcRoot.isDirectory) {
            fail("Source directory does not exist at ${srcRoot.absolutePath}")
        }

        val productionRoots = (srcRoot.listFiles { f -> f.isDirectory } ?: emptyArray())
            .filter { it.name !in nonProductionSourceSets }
            .map { File(it, "java") }
            .filter { it.isDirectory }

        if (productionRoots.isEmpty()) {
            fail(
                "Found zero production source roots under ${srcRoot.absolutePath}/*/java " +
                    "(excluding $nonProductionSourceSets); the module's source-set layout " +
                    "may have changed",
            )
        }

        val kotlinFiles = mutableListOf<File>()
        fun walk(dir: File) {
            for (entry in dir.listFiles() ?: emptyArray()) {
                if (entry.isDirectory) {
                    walk(entry)
                } else if (entry.isFile && entry.name.endsWith(".kt")) {
                    kotlinFiles.add(entry)
                }
            }
        }
        productionRoots.forEach(::walk)

        if (kotlinFiles.isEmpty()) {
            fail(
                "Scanned zero .kt files under ${productionRoots.map { it.path }}; the " +
                    "source tree is empty or path resolution failed",
            )
        }

        if (kotlinFiles.none { it.name == "AdbConnection.kt" }) {
            fail(
                "AdbConnection.kt was not found among ${kotlinFiles.size} scanned .kt " +
                    "files: ${kotlinFiles.map { it.path }}",
            )
        }

        return kotlinFiles
    }

    /**
     * Strips block/KDoc comments (a full `/* ... */` span, however many lines it spans),
     * then `//` line comments -- tracking whether each line is inside a `"..."` literal so
     * a `//` inside a string (e.g. a URL) survives rather than truncating real code.
     */
    private fun stripComments(text: String): String {
        val withoutBlockComments = blockComment.replace(text, "")
        return withoutBlockComments.lineSequence().joinToString("\n") { line ->
            var inString = false
            var cut = line.length
            var i = 0
            while (i < line.length - 1) {
                val c = line[i]
                if (c == '"' && (i == 0 || line[i - 1] != '\\')) inString = !inString
                if (!inString && c == '/' && line[i + 1] == '/') {
                    cut = i
                    break
                }
                i++
            }
            line.substring(0, cut)
        }
    }

    @Test
    fun productionSourceSetsAreDiscoveredAndAdbConnectionIsAmongThem() {
        // Exercises the discovery step and its vacuity guards on their own: a failure here
        // means the scan itself is broken, before any allowlist or argument rule runs.
        val files = productionKotlinFiles()
        check(files.any { it.name == "AdbConnection.kt" })
    }

    @Test
    fun onlyAdbConnectionConstructsASocket() {
        val files = productionKotlinFiles()
        val constructors = files.filter {
            socketConstruction.containsMatchIn(stripComments(it.readText()))
        }

        val offenders = constructors.filterNot { it.name == "AdbConnection.kt" }
        if (offenders.isNotEmpty()) {
            fail(
                "Production file(s) construct a Socket outside AdbConnection.kt, the only " +
                    "file allowed to: ${offenders.map { it.path }}",
            )
        }

        if (constructors.none { it.name == "AdbConnection.kt" }) {
            fail("AdbConnection.kt no longer constructs a Socket; this test would otherwise be vacuous")
        }
    }

    @Test
    fun adbConnectionOnlyEverConstructsTheBareNoArgumentSocket() {
        val files = productionKotlinFiles()
        val adbConnectionFile = files.first { it.name == "AdbConnection.kt" }
        val text = stripComments(adbConnectionFile.readText())

        val offenders = socketConstruction.findAll(text).mapNotNull { match ->
            var i = match.range.last + 1
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != ')') {
                val snippet = text.substring(match.range.first, minOf(text.length, i + 40))
                snippet.trim()
            } else {
                null
            }
        }.toList()

        if (offenders.isNotEmpty()) {
            fail(
                "AdbConnection.kt constructs a Socket with a named host instead of the " +
                    "bare Socket() form: $offenders",
            )
        }
    }

    @Test
    fun noProductionSourceReferencesAnotherOutboundNetworkSurface() {
        val files = productionKotlinFiles()
        val offenders = mutableListOf<String>()
        for (file in files) {
            val text = stripComments(file.readText())
            for (token in otherEgressTokens) {
                if (text.contains(token)) offenders.add("${file.path}: $token")
            }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Production source references an outbound-network surface other than the " +
                    "loopback Socket: $offenders",
            )
        }
    }
}
