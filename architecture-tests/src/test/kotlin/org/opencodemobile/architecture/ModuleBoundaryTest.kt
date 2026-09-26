package org.opencodemobile.architecture

import com.lemonapp.konsist.Konsist
import com.lemonapp.konsist.assertions.assertTrue
import com.lemonapp.konsist.koin.KonsistKoin
import com.lemonapp.konsist.scope.*
import com.lemonapp.konsist.test.assertion.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.io.File

class ModuleBoundaryTest : StringSpec() {

    private val projectRoot = File("").absoluteFile.parentFile.parentFile
    private val sourceDirs = Konsist
        .scopeFromDirectories(
            projectRoot.resolve("shared/domain/src/commonMain/kotlin"),
            projectRoot.resolve("shared/application/src/commonMain/kotlin"),
            projectRoot.resolve("shared/data/src/commonMain/kotlin"),
            projectRoot.resolve("shared/networking/src/commonMain/kotlin"),
            projectRoot.resolve("shared/realtime/src/commonMain/kotlin"),
            projectRoot.resolve("shared/persistence/src/commonMain/kotlin"),
            projectRoot.resolve("shared/security/src/commonMain/kotlin"),
            projectRoot.resolve("shared/test-support/src/commonMain/kotlin"),
            projectRoot.resolve("features/connection/src/commonMain/kotlin"),
            projectRoot.resolve("features/projects/src/commonMain/kotlin"),
            projectRoot.resolve("features/sessions/src/commonMain/kotlin"),
            projectRoot.resolve("features/transcript/src/commonMain/kotlin"),
            projectRoot.resolve("features/composer/src/commonMain/kotlin"),
            projectRoot.resolve("features/files/src/commonMain/kotlin"),
            projectRoot.resolve("features/permissions/src/commonMain/kotlin"),
            projectRoot.resolve("features/settings/src/commonMain/kotlin"),
            projectRoot.resolve("design-system/src/commonMain/kotlin"),
            projectRoot.resolve("androidApp/src/main/kotlin"),
        )

    init {
        "Domain layer must not depend on data, networking, realtime, persistence, security, Ktor, Compose, SQLDelight, Koin, or generated client" {
            val domain = Layer("Domain", sourceDirs.files("**/shared/domain/**"))
            val forbiddenLayers = listOf(
                Layer("Data", sourceDirs.files("**/shared/data/**")),
                Layer("Networking", sourceDirs.files("**/shared/networking/**")),
                Layer("Realtime", sourceDirs.files("**/shared/realtime/**")),
                Layer("Persistence", sourceDirs.files("**/shared/persistence/**")),
                Layer("Security", sourceDirs.files("**/shared/security/**")),
                Layer("TestSupport", sourceDirs.files("**/shared/test-support/**")),
                Layer("Features", sourceDirs.files("**/features/**")),
                Layer("DesignSystem", sourceDirs.files("**/design-system/**")),
                Layer("AndroidApp", sourceDirs.files("**/androidApp/**")),
            )
            KonsistLayerAssertions
                .layer(domain)
                .shouldNotDependOnAny(forbiddenLayers)
                .assertTrue()

            // Also check for forbidden imports
            domain
                .classes()
                .imports("io.ktor.**", "org.jetbrains.compose.**", "androidx.compose.**", "app.cash.sqldelight.**", "io.insert-koin.**", "org.opencode.mobile.networking.client.generated.**")
                .shouldBeEmpty()
                .assertTrue()
        }

        "Application layer must only depend on Domain and Kotlin stdlib" {
            val application = Layer("Application", sourceDirs.files("**/shared/application/**"))
            val domain = Layer("Domain", sourceDirs.files("**/shared/domain/**"))

            KonsistLayerAssertions
                .layer(application)
                .shouldOnlyDependOn(domain)
                .assertTrue()

            application
                .classes()
                .imports("io.ktor.**", "org.jetbrains.compose.**", "androidx.compose.**", "app.cash.sqldelight.**", "io.insert-koin.**", "org.opencode.mobile.networking.client.generated.**")
                .shouldBeEmpty()
                .assertTrue()
        }

        "Data layer must only depend on Domain, Networking, Realtime, Persistence, Security" {
            val data = Layer("Data", sourceDirs.files("**/shared/data/**"))
            val allowedLayers = listOf(
                Layer("Domain", sourceDirs.files("**/shared/domain/**")),
                Layer("Networking", sourceDirs.files("**/shared/networking/**")),
                Layer("Realtime", sourceDirs.files("**/shared/realtime/**")),
                Layer("Persistence", sourceDirs.files("**/shared/persistence/**")),
                Layer("Security", sourceDirs.files("**/shared/security/**")),
            )
            KonsistLayerAssertions
                .layer(data)
                .shouldOnlyDependOn(*allowedLayers)
                .assertTrue()

            data
                .classes()
                .imports("org.jetbrains.compose.**", "androidx.compose.**", "io.insert-koin.**")
                .shouldBeEmpty()
                .assertTrue()
        }

        "Networking layer must only depend on Domain and Ktor" {
            val networking = Layer("Networking", sourceDirs.files("**/shared/networking/**"))
            val domain = Layer("Domain", sourceDirs.files("**/shared/domain/**"))

            KonsistLayerAssertions
                .layer(networking)
                .shouldOnlyDependOn(domain)
                .assertTrue()

            networking
                .classes()
                .imports("org.jetbrains.compose.**", "androidx.compose.**", "app.cash.sqldelight.**", "io.insert-koin.**")
                .shouldBeEmpty()
                .assertTrue()

            // Generated client must stay in networking
            networking
                .classes()
                .imports("org.opencode.mobile.networking.client.generated.**")
                .shouldNotBeEmpty()
                .assertTrue()
        }

        "Realtime layer must only depend on Domain and Networking" {
            val realtime = Layer("Realtime", sourceDirs.files("**/shared/realtime/**"))
            val allowedLayers = listOf(
                Layer("Domain", sourceDirs.files("**/shared/domain/**")),
                Layer("Networking", sourceDirs.files("**/shared/networking/**")),
            )
            KonsistLayerAssertions
                .layer(realtime)
                .shouldOnlyDependOn(*allowedLayers)
                .assertTrue()

            realtime
                .classes()
                .imports("org.jetbrains.compose.**", "androidx.compose.**", "app.cash.sqldelight.**", "io.insert-koin.**", "org.opencode.mobile.networking.client.generated.**")
                .shouldBeEmpty()
                .assertTrue()
        }

        "Persistence layer must only depend on Domain and SQLDelight" {
            val persistence = Layer("Persistence", sourceDirs.files("**/shared/persistence/**"))
            val domain = Layer("Domain", sourceDirs.files("**/shared/domain/**"))

            KonsistLayerAssertions
                .layer(persistence)
                .shouldOnlyDependOn(domain)
                .assertTrue()

            persistence
                .classes()
                .imports("io.ktor.**", "org.jetbrains.compose.**", "androidx.compose.**", "io.insert-koin.**", "org.opencode.mobile.networking.client.generated.**")
                .shouldBeEmpty()
                .assertTrue()
        }

        "Security layer must only depend on Domain" {
            val security = Layer("Security", sourceDirs.files("**/shared/security/**"))
            val domain = Layer("Domain", sourceDirs.files("**/shared/domain/**"))

            KonsistLayerAssertions
                .layer(security)
                .shouldOnlyDependOn(domain)
                .assertTrue()

            security
                .classes()
                .imports("io.ktor.**", "org.jetbrains.compose.**", "androidx.compose.**", "app.cash.sqldelight.**", "io.insert-koin.**", "org.opencode.mobile.networking.client.generated.**")
                .shouldBeEmpty()
                .assertTrue()
        }

        "Test-support layer can depend on shared layers for test utilities" {
            val testSupport = Layer("TestSupport", sourceDirs.files("**/shared/test-support/**"))
            val allowedLayers = listOf(
                Layer("Domain", sourceDirs.files("**/shared/domain/**")),
                Layer("Application", sourceDirs.files("**/shared/application/**")),
                Layer("Networking", sourceDirs.files("**/shared/networking/**")),
            )
            KonsistLayerAssertions
                .layer(testSupport)
                .shouldOnlyDependOn(*allowedLayers)
                .assertTrue()
        }

        "Each feature must only depend on Domain, Application, DesignSystem, Compose, Koin, and stdlib" {
            val features = listOf(
                "connection", "projects", "sessions", "transcript", "composer",
                "files", "permissions", "settings"
            )

            for (featureName in features) {
                val feature = Layer("Feature:$featureName", sourceDirs.files("**/features/$featureName/**"))
                val domain = Layer("Domain", sourceDirs.files("**/shared/domain/**"))
                val application = Layer("Application", sourceDirs.files("**/shared/application/**"))
                val designSystem = Layer("DesignSystem", sourceDirs.files("**/design-system/**"))

                KonsistLayerAssertions
                    .layer(feature)
                    .shouldOnlyDependOn(domain, application, designSystem)
                    .assertTrue()

                // Features must not depend on other features
                val otherFeatures = features.filter { it != featureName }
                    .map { Layer("Feature:$it", sourceDirs.files("**/features/$it/**")) }
                KonsistLayerAssertions
                    .layer(feature)
                    .shouldNotDependOnAny(otherFeatures)
                    .assertTrue()

                // Features must not directly import forbidden modules
                feature
                    .classes()
                    .imports("io.ktor.**", "app.cash.sqldelight.**", "org.opencode.mobile.networking.client.generated.**", "org.opencodemobile.shared.persistence.**", "org.opencodemobile.shared.realtime.**", "org.opencodemobile.shared.networking.**", "org.opencodemobile.shared.security.**", "org.opencodemobile.shared.data.**")
                    .shouldBeEmpty()
                    .assertTrue()
            }
        }

        "Features must not reach into another feature's internals (no cross-feature imports)" {
            val features = listOf(
                "connection", "projects", "sessions", "transcript", "composer",
                "files", "permissions", "settings"
            )

            for (featureName in features) {
                val featureScope = sourceDirs.files("**/features/$featureName/**")
                val otherFeatures = features.filter { it != featureName }
                    .flatMap { sourceDirs.files("**/features/$it/**").directories() }

                featureScope
                    .classes()
                    .imports("org.opencodemobile.features.*")
                    .filter { importEntry ->
                        otherFeatures.any { other -> importEntry.packageName.startsWith("org.opencodemobile.features.${other.name}") }
                    }
                    .shouldBeEmpty()
                    .assertTrue()
            }
        }

        "Design system must only depend on Compose and Kotlin stdlib" {
            val designSystem = Layer("DesignSystem", sourceDirs.files("**/design-system/**"))

            designSystem
                .classes()
                .imports("io.ktor.**", "app.cash.sqldelight.**", "io.insert-koin.**", "org.opencode.mobile.networking.client.generated.**", "org.opencodemobile.shared.**", "org.opencodemobile.features.**")
                .shouldBeEmpty()
                .assertTrue()
        }

        "Generated OpenAPI client must not be hand-edited (no non-generated code in generated package)" {
            val generatedClient = sourceDirs.files("**/shared/networking/src/commonMain/kotlin/org/opencode/mobile/networking/client/generated/**")

            generatedClient
                .files()
                .filter { file ->
                    file.name.endsWith(".kt") &&
                    !file.name.contains("Models") &&
                    !file.name.contains("OpenCodeApiClient")
                }
                .shouldBeEmpty()
                .assertTrue()

            // Check that generated files have the generated marker comment
            generatedClient
                .files()
                .filter { it.name.endsWith(".kt") }
                .forEach { file ->
                    val content = file.readText()
                    (content.contains("@file:Suppress(\"UNUSED_PARAMETER\")") ||
                     content.contains("Generated by") ||
                     content.contains("DO NOT EDIT"))
                        .shouldBeTrue()
                }
        }

        "UI/ViewModel code must not directly touch HTTP/SSE/SQLDelight/SecureStore" {
            // Features (UI/ViewModel) should not import these directly
            val features = sourceDirs.files("**/features/**")
            val designSystem = sourceDirs.files("**/design-system/**")
            val androidApp = sourceDirs.files("**/androidApp/**")

            val uiLayers = listOf(features, designSystem, androidApp)

            for (layer in uiLayers) {
                layer
                    .classes()
                    .imports("io.ktor.**", "io.ktor.client.**", "app.cash.sqldelight.**", "androidx.security.**", "androidx.datastore.preferences.**")
                    .shouldBeEmpty()
                    .assertTrue()
            }
        }

        "Koin modules must only be declared in composition root (androidApp) and features" {
            // Koin modules should only be in androidApp and features
            val allowedKoinLocations = listOf(
                sourceDirs.files("**/androidApp/**"),
                sourceDirs.files("**/features/**"),
            )

            sourceDirs
                .files("**/shared/**")
                .classes()
                .annotatedWith("io.insert.koin.module")
                .shouldBeEmpty()
                .assertTrue()

            sourceDirs
                .files("**/design-system/**")
                .classes()
                .annotatedWith("io.insert.koin.module")
                .shouldBeEmpty()
                .assertTrue()
        }
    }
}