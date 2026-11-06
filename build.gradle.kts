plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("multiplatform") version "2.1.20" apply false
    kotlin("plugin.serialization") version "2.1.20" apply false
    id("io.ktor.plugin") version "3.1.3" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

allprojects { group = "rw.rutongo.mmems"; version = "0.1.0-SNAPSHOT" }

subprojects {
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
        parallel = true
        baseline = file("$projectDir/detekt-baseline.xml")
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }
}
