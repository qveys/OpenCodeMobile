package org.opencodemobile.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Executable form of the dictation rejection criterion in
 * `docs/adr/on-device-speech-to-text.md` and `docs/ARCHITECTURE.md`
 * §"Speech-to-text dictation and on-device enforcement (T5)".
 *
 * V1 must ship **only** recognition entry points the platform documents as
 * on-device-only, or disable dictation. Any dictation code path that can reach a
 * network-capable recognizer is an automatic review rejection; this test makes
 * the two critical shapes fail CI instead of relying on a reviewer noticing:
 *
 * - the network-capable Android factory `createSpeechRecognizer(...)` and the
 *   non-binding `EXTRA_PREFER_OFFLINE` hint must not appear anywhere;
 * - the only recognizer factory used must be
 *   `createOnDeviceSpeechRecognizer`, and only in the Android provider;
 * - the iOS provider must both gate on `supportsOnDeviceRecognition` and set
 *   `requiresOnDeviceRecognition = true`.
 *
 * Comments are stripped before matching so documentation that names the
 * forbidden APIs (this is where the contract is explained) does not trip the
 * gate.
 */
class DictationOnDeviceContractTest : StringSpec({

    val repoRoot = File(".")
    val ignoredSegments = setOf("build", ".git", ".gradle", ".kotlin", ".paperclip", "architecture-tests")

    fun relative(file: File): String = file.invariantSeparatorsPath.removePrefix("./")

    val allKotlin = repoRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filterNot { file -> relative(file).split('/').any { it in ignoredSegments } }
        .toList()

    val androidProvider =
        "features/composer/src/androidMain/kotlin/org/opencodemobile/features/composer/AndroidOnDeviceDictationProvider.kt"
    val iosProvider =
        "features/composer/src/iosMain/kotlin/org/opencodemobile/features/composer/IosOnDeviceDictationProvider.kt"

    fun source(path: String): String = stripComments(File(repoRoot, path).readText())

    "no V1 source can reach the network-capable Android recognizer factory" {
        val offenders = allKotlin
            .filter { Regex("""\bcreateSpeechRecognizer\s*\(""").containsMatchIn(stripComments(it.readText())) }
            .map(::relative)
        offenders shouldBe emptyList()
    }

    "no V1 source relies on the non-binding EXTRA_PREFER_OFFLINE hint" {
        val offenders = allKotlin
            .filter { "EXTRA_PREFER_OFFLINE" in stripComments(it.readText()) }
            .map(::relative)
        offenders shouldBe emptyList()
    }

    "the only Android recognizer factory used is createOnDeviceSpeechRecognizer" {
        val factories = allKotlin
            .flatMap { Regex("""\bcreate[A-Za-z]*SpeechRecognizer\b""").findAll(stripComments(it.readText())).toList() }
            .map { it.value }
            .toSet()
        factories shouldBe setOf("createOnDeviceSpeechRecognizer")
    }

    "the on-device recognizer is created only in the Android provider" {
        val files = allKotlin
            .filter { "createOnDeviceSpeechRecognizer" in stripComments(it.readText()) }
            .map(::relative)
            .toSet()
        files shouldBe setOf(androidProvider)
    }

    "the Android provider gates on the API 33+ capability check and maps the language errors" {
        val code = source(androidProvider)
        code shouldContain "createOnDeviceSpeechRecognizer"
        code shouldContain "isOnDeviceRecognitionAvailable"
        code shouldContain "ERROR_LANGUAGE_NOT_SUPPORTED"
        code shouldContain "ERROR_LANGUAGE_UNAVAILABLE"
    }

    "the Android provider declares the microphone permission and never launches an activity" {
        val code = source(androidProvider)
        code shouldContain "RECORD_AUDIO"
        code shouldContain "RequestPermission"
        ("startActivity" in code) shouldBe false
    }

    "the iOS provider gates on supportsOnDeviceRecognition and forces requiresOnDeviceRecognition" {
        val code = source(iosProvider)
        code shouldContain "supportsOnDeviceRecognition"
        code shouldContain "requiresOnDeviceRecognition = true"
    }

    "the iOS app declares microphone and speech-recognition usage descriptions" {
        val plist = File(repoRoot, "iosApp/iosApp/Info.plist").readText()
        plist shouldContain "NSMicrophoneUsageDescription"
        plist shouldContain "NSSpeechRecognitionUsageDescription"
    }
})

/**
 * Removes `//` line and `/* */` block comments (including KDoc) while leaving
 * string and char literals intact, so forbidden identifiers inside prose do not
 * match. Triple-quoted raw strings are preserved as-is.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun stripComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    var inString = false
    var inRawString = false
    var inChar = false
    var inLineComment = false
    var inBlockComment = false

    while (i < source.length) {
        val c = source[i]
        val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
        val third = if (i + 2 < source.length) source[i + 2] else '\u0000'

        when {
            inLineComment -> if (c == '\n') {
                inLineComment = false
                out.append(c)
            }

            inBlockComment -> if (c == '*' && next == '/') {
                inBlockComment = false
                i++
            }

            inRawString -> {
                out.append(c)
                if (c == '"' && next == '"' && third == '"') {
                    out.append("\"\"")
                    inRawString = false
                    i += 2
                }
            }

            inString -> {
                out.append(c)
                if (c == '\\' && i + 1 < source.length) {
                    out.append(source[i + 1])
                    i++
                } else if (c == '\n') {
                    inString = false
                } else if (c == '"') {
                    inString = false
                }
            }

            inChar -> {
                out.append(c)
                if (c == '\\' && i + 1 < source.length) {
                    out.append(source[i + 1])
                    i++
                } else if (c == '\'') {
                    inChar = false
                }
            }

            c == '/' && next == '/' -> inLineComment = true

            c == '/' && next == '*' -> {
                inBlockComment = true
                i++
            }

            c == '"' && next == '"' && third == '"' -> {
                out.append("\"\"\"")
                inRawString = true
                i += 2
            }

            c == '"' -> {
                inString = true
                out.append(c)
            }

            c == '\'' -> {
                inChar = true
                out.append(c)
            }

            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}
