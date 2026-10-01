package org.opencodemobile.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoImportDeclaration
import com.lemonappdev.konsist.api.verify.assertEmpty
import com.lemonappdev.konsist.api.verify.assertNotEmpty
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.StringSpec

/**
 * Executable form of the module-boundary rules in
 * *Cahier des charges d'architecture v1.0* §5.2 (and ADR 0004).
 *
 * The spec names five forbidden shapes:
 *   1. `domain` → data, platform, Ktor, Compose, SQLDelight, Koin, generated client
 *   2. UI / ViewModel → HTTP, SSE, SQLDelight, SecureStore
 *   3. UI → generated OpenAPI types
 *   4. feature A → internals of feature B
 *   5. manual modification of generated code
 *
 * ADR 0004 additionally fixes the direction of the shared layers (application,
 * data, networking, realtime, persistence, security) and the Koin composition
 * root. This test encodes all of them as static, import-level assertions so the
 * gate is enforced by CI rather than by review.
 *
 * NOTE: the allowed `networking` dependency set is `domain` + `security`, not
 * `domain` alone. `OpenCodeV2Adapter`/`HttpClientFactory` consume the T1
 * `ServerIdentityGate` ports implemented by `shared/security`
 * (ARCHITECTURE.md §3.1); this is the intended seam, not a violation.
 */
class ModuleBoundaryTest : StringSpec({

    // The Gradle Test task runs from the root project (see build.gradle.kts), and
    // Konsist resolves scope paths against the project root it detects there, so
    // every path below is repository-relative.

    // --- internal module package roots (import prefixes) ----------------------
    val domain = "org.opencodemobile.shared.domain."
    val application = "org.opencodemobile.shared.application."
    val data = "org.opencodemobile.shared.data."
    val networking = "org.opencodemobile.shared.networking."
    val realtime = "org.opencodemobile.shared.realtime."
    val persistence = "org.opencodemobile.shared.persistence."
    val security = "org.opencodemobile.shared.security."
    val testSupport = "org.opencodemobile.shared.testsupport."
    val designSystem = "org.opencodemobile.design."
    val features = "org.opencodemobile.features."
    val androidApp = "org.opencodemobile.android."
    val generated = "org.opencode.mobile.networking.client.generated."

    // --- external frameworks --------------------------------------------------
    val ktor = "io.ktor."
    val compose = "org.jetbrains.compose."
    val composeAndroidx = "androidx.compose."
    val sqldelight = "app.cash.sqldelight."
    val koin = "io.insert.koin."
    val secureStore = "androidx.security."
    val keystore = "android.security.keystore."

    val internalModules = listOf(
        domain, application, data, networking, realtime, persistence,
        security, testSupport, designSystem, features, androidApp, generated,
    )

    val featureNames = listOf(
        "connection", "projects", "sessions", "transcript",
        "composer", "files", "permissions", "settings",
    )

    val sharedCommonMain = listOf(
        "shared/domain", "shared/application", "shared/data", "shared/networking",
        "shared/realtime", "shared/persistence", "shared/security",
        "shared/test-support", "shared/tls-test-support",
    )

    /**
     * Builds a scope from a repository-relative source directory and fails fast
     * if the directory contains no Kotlin file — an empty scope would make every
     * assertion below pass vacuously, which is the exact failure this gate exists
     * to prevent.
     */
    fun scopeOf(path: String): KoScope {
        val scope = Konsist.scopeFromDirectory(path)
        scope.files.assertNotEmpty()
        return scope
    }

    fun KoScope.importsUnder(prefixes: Collection<String>): List<KoImportDeclaration> =
        imports.filter { import -> prefixes.any { import.name.startsWith(it) } }

    /** Every internal-module import must be either the module's own package or explicitly allowed. */
    fun assertInternalDependencies(
        scope: KoScope,
        selfPrefixes: List<String>,
        allowedPrefixes: List<String>,
    ) {
        scope.imports
            .filter { import ->
                val name = import.name
                internalModules.any { name.startsWith(it) } &&
                    selfPrefixes.none { name.startsWith(it) } &&
                    allowedPrefixes.none { name.startsWith(it) }
            }
            .assertEmpty()
    }

    fun assertNoFrameworkImports(scope: KoScope, prefixes: Collection<String>) {
        scope.importsUnder(prefixes).assertEmpty()
    }

    val uiForbiddenFrameworks = listOf(ktor, sqldelight, secureStore, keystore)
    val uiForbiddenFrameworksAndGenerated = uiForbiddenFrameworks + generated

    // --- §5.2 / ADR 0004: layer directions -----------------------------------

    // ADR 0001 §5, amended by OPE-245: the domain owns the coroutine types its port
    // interfaces expose (`Flow`, `StateFlow`, `SharedFlow`, `CoroutineScope`), so
    // `kotlinx-coroutines-core` is the one allowed dependency besides the stdlib.
    "shared/domain depends only on the Kotlin stdlib and kotlinx-coroutines-core" {
        val scope = scopeOf("shared/domain/src/commonMain/kotlin")
        assertInternalDependencies(scope, selfPrefixes = listOf(domain), allowedPrefixes = emptyList())
        assertNoFrameworkImports(
            scope,
            listOf(ktor, compose, composeAndroidx, sqldelight, koin, secureStore, keystore),
        )
    }

    "shared/application may only depend on shared/domain" {
        val scope = scopeOf("shared/application/src/commonMain/kotlin")
        assertInternalDependencies(scope, selfPrefixes = listOf(application), allowedPrefixes = listOf(domain))
        assertNoFrameworkImports(
            scope,
            listOf(ktor, compose, composeAndroidx, sqldelight, koin, secureStore, keystore, generated),
        )
    }

    "shared/data may only depend on domain, networking, realtime, persistence and security" {
        val scope = scopeOf("shared/data/src/commonMain/kotlin")
        assertInternalDependencies(
            scope,
            selfPrefixes = listOf(data),
            allowedPrefixes = listOf(domain, networking, realtime, persistence, security),
        )
        assertNoFrameworkImports(scope, listOf(compose, composeAndroidx, koin, secureStore, keystore, generated))
    }

    "shared/networking may only depend on domain and security, and owns the generated client" {
        val scope = scopeOf("shared/networking/src/commonMain/kotlin")
        assertInternalDependencies(
            scope,
            selfPrefixes = listOf(networking),
            allowedPrefixes = listOf(domain, security, generated),
        )
        assertNoFrameworkImports(scope, listOf(compose, composeAndroidx, sqldelight, koin, secureStore, keystore))
    }

    "shared/realtime may only depend on domain and networking" {
        val scope = scopeOf("shared/realtime/src/commonMain/kotlin")
        assertInternalDependencies(scope, selfPrefixes = listOf(realtime), allowedPrefixes = listOf(domain, networking))
        assertNoFrameworkImports(
            scope,
            listOf(compose, composeAndroidx, sqldelight, koin, secureStore, keystore, generated),
        )
    }

    "shared/persistence may only depend on domain" {
        val scope = scopeOf("shared/persistence/src/commonMain/kotlin")
        assertInternalDependencies(scope, selfPrefixes = listOf(persistence), allowedPrefixes = listOf(domain))
        assertNoFrameworkImports(
            scope,
            listOf(ktor, compose, composeAndroidx, koin, secureStore, keystore, generated),
        )
    }

    "shared/security may only depend on domain" {
        val scope = scopeOf("shared/security/src/commonMain/kotlin")
        assertInternalDependencies(scope, selfPrefixes = listOf(security), allowedPrefixes = listOf(domain))
        assertNoFrameworkImports(
            scope,
            listOf(ktor, compose, composeAndroidx, sqldelight, koin, secureStore, keystore, generated),
        )
    }

    "shared/test-support may only depend on domain, application and networking" {
        val scope = scopeOf("shared/test-support/src/commonMain/kotlin")
        assertInternalDependencies(
            scope,
            selfPrefixes = listOf(testSupport),
            allowedPrefixes = listOf(domain, application, networking),
        )
        assertNoFrameworkImports(scope, listOf(compose, composeAndroidx, koin, secureStore, keystore, generated))
    }

    "shared/tls-test-support may only depend on domain and test-support" {
        val scope = scopeOf("shared/tls-test-support/src/commonMain/kotlin")
        assertInternalDependencies(
            scope,
            selfPrefixes = listOf(testSupport),
            allowedPrefixes = listOf(domain, testSupport),
        )
        assertNoFrameworkImports(scope, listOf(compose, composeAndroidx, koin, secureStore, keystore, generated))
    }

    "design-system may only depend on Compose and the Kotlin standard library" {
        val scope = scopeOf("design-system/src/commonMain/kotlin")
        assertInternalDependencies(scope, selfPrefixes = listOf(designSystem), allowedPrefixes = emptyList())
        assertNoFrameworkImports(scope, listOf(ktor, sqldelight, koin, secureStore, keystore, generated))
    }

    // --- §5.2: feature isolation ---------------------------------------------

    for (featureName in featureNames) {
        "feature '$featureName' may only depend on domain, application and design-system" {
            val scope = scopeOf("features/$featureName/src/commonMain/kotlin")
            assertInternalDependencies(
                scope,
                selfPrefixes = listOf("$features$featureName."),
                allowedPrefixes = listOf(domain, application, designSystem),
            )
            assertNoFrameworkImports(
                scope,
                listOf(ktor, sqldelight, secureStore, keystore, generated),
            )
        }
    }

    "features must not reach into another feature's internals" {
        val otherFeatures = featureNames.flatMap { featureName ->
            scopeOf("features/$featureName/src/commonMain/kotlin")
                .importsUnder(listOf(features))
                .filter { import -> !import.name.startsWith("$features$featureName.") }
        }
        otherFeatures.assertEmpty()
    }

    // --- §5.2: UI / ViewModel must not touch infrastructure --------------------

    "UI/ViewModel code must not import HTTP/SSE, SQLDelight, SecureStore or generated types" {
        val uiScopes = featureNames.map { scopeOf("features/$it/src/commonMain/kotlin") } +
            scopeOf("design-system/src/commonMain/kotlin") +
            scopeOf("androidApp/src/main/kotlin")
        uiScopes.forEach { scope -> scope.importsUnder(uiForbiddenFrameworksAndGenerated).assertEmpty() }
    }

    "androidApp may depend on any module except the generated OpenAPI client" {
        val scope = scopeOf("androidApp/src/main/kotlin")
        assertInternalDependencies(
            scope,
            selfPrefixes = listOf(androidApp),
            allowedPrefixes = listOf(
                domain, application, data, networking, realtime,
                persistence, security, testSupport, designSystem, features,
            ),
        )
        assertNoFrameworkImports(scope, uiForbiddenFrameworks)
    }

    // --- ADR 0004: Koin composition root --------------------------------------

    "Koin module declarations live only in androidApp and features/*" {
        sharedCommonMain.forEach { module ->
            scopeOf("$module/src/commonMain/kotlin").importsUnder(listOf(koin)).assertEmpty()
        }
        scopeOf("design-system/src/commonMain/kotlin").importsUnder(listOf(koin)).assertEmpty()
    }

    // --- §5.2: generated code is never hand-edited ----------------------------

    "the generated OpenAPI client is never hand-edited" {
        val generatedFiles = scopeOf(
            "shared/networking/src/commonMain/kotlin/org/opencode/mobile/networking/client/generated",
        ).files

        generatedFiles.assertNotEmpty()
        generatedFiles.forEach { file ->
            file.assertTrue { it.text.contains("AUTO-GENERATED") || it.text.contains("DO NOT MODIFY") }
        }
    }
})
