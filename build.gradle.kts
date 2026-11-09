plugins {
    kotlin("jvm") version "2.1.21" apply false
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    kotlin("plugin.compose") version "2.1.21" apply false
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("app.cash.sqldelight") version "2.1.0" apply false
    id("io.ktor.plugin") version "3.1.3" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
}

allprojects { group = "rw.rutongo.mmems"; version = "0.1.0-SNAPSHOT" }

subprojects {
    plugins.withId("io.gitlab.arturbosch.detekt") {
        configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            val moduleDir = layout.projectDirectory
            val rootConfig = rootProject.layout.projectDirectory.file("config/detekt/detekt.yml").asFile
            val moduleConfig = moduleDir.file("detekt.yml").asFile
            config.setFrom(files(rootConfig, moduleConfig).filter { it.exists() })
            baseline = moduleDir.file("detekt-baseline.xml").asFile
            buildUponDefaultConfig = true
            parallel = true
        }
    }
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
    }
}
