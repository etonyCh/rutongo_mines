import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvm()
    androidTarget()
    wasmJs {
        outputModuleName.set("rutongo-mmems-shared")
        browser()
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    namespace = "rw.rutongo.mmems.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    excludes.add("rw.rutongo.mmems.shared.time.ShiftResolverTest")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    setSource(files("src/commonMain/kotlin", "src/commonTest/kotlin"))
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    setSource(files("src/commonMain/kotlin", "src/commonTest/kotlin"))
}
