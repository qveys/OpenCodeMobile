package org.opencodemobile.design.system.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

/** Prevents the audited user-visible copy from moving back into Compose source. */
class NoAuditedHardcodedUiStringsTest {
    private val projectRoot = File(System.getProperty("user.dir")).parentFile

    @Test
    fun auditedStringsUseComposeResources() {
        val auditedLiterals = mapOf(
            "androidApp/src/main/kotlin/org/opencodemobile/android/MainActivity.kt" to listOf(
                "OpenCode Mobile", "Back",
            ),
            "features/transcript/src/commonMain/kotlin/org/opencodemobile/features/transcript/TranscriptScreen.kt" to listOf(
                "Abort", "Aborting…", "Aborted — waiting for the server snapshot…",
                "Loading transcript…", "No transcript yet. Send a prompt to start the turn.",
                "YOU", "AGENT", "SYSTEM", "TOOL", "MESSAGE",
            ),
            "features/questions/src/commonMain/kotlin/org/opencodemobile/features/questions/PendingQuestionsScreen.kt" to listOf(
                "Answer",
            ),
            "features/catalog/src/commonMain/kotlin/org/opencodemobile/features/catalog/ServerCatalogScreen.kt" to listOf(
                "Models & agents", "What this server exposes. Nothing is bundled with the app.",
                "Loading models and agents…", "Retry", "No models exposed by this provider.",
                "Agents", "Models & agents are unavailable until a server is connected.",
            ),
            "features/composer/src/commonMain/kotlin/org/opencodemobile/features/composer/ComposerBar.kt" to listOf(
                "Send a prompt", "Send",
            ),
            "design-system/src/commonMain/kotlin/org/opencodemobile/design/system/SessionRow.kt" to listOf(
                "Session \$title, \${status.name.lowercase()}",
            ),
        )

        for ((relativePath, literals) in auditedLiterals) {
            val source = File(projectRoot, relativePath).readText()
            for (literal in literals) {
                assertFalse(
                    "\"$literal\"" in source,
                    "$relativePath still contains audited hardcoded UI text: $literal",
                )
            }
        }
    }
}
