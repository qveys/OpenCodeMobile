plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvm()
    sourceSets {
        val test by getting {
            dependencies {
                implementation(libs.konsist)
                implementation(libs.konsistTest)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotestCore)
                implementation(libs.kotestAssertions)
                implementation(libs.kotestProperty)
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().named("compileTestKotlin").configure {
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
}