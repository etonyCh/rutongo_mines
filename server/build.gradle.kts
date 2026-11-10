import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    id("org.jetbrains.kotlinx.kover")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    application
}

application { mainClass.set("rw.rutongo.mmems.ApplicationKt") }

tasks.named<ShadowJar>("shadowJar") { mergeServiceFiles() }

val verifyFatJarServices by tasks.registering {
    dependsOn(tasks.named("shadowJar"))
    val archive = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    inputs.file(archive)
    doLast {
        val serviceFile =
            zipTree(archive.get().asFile)
                .matching { include("META-INF/services/org.flywaydb.core.extensibility.Plugin") }
                .singleFile
        check(serviceFile.readText().contains("org.flywaydb.core.internal.resource.CoreResourceTypeProvider")) {
            "Fat jar is missing Flyway service metadata; ShadowJar.mergeServiceFiles() is required"
        }
    }
}

tasks.named("check") { dependsOn(verifyFatJarServices) }

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-core-jvm:3.1.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.1.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.1.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.1.3")
    implementation("io.ktor:ktor-server-rate-limit-jvm:3.1.3")
    implementation("io.ktor:ktor-server-cors-jvm:3.1.3")
    implementation("io.insert-koin:koin-ktor:4.0.2")
    implementation("io.insert-koin:koin-logger-slf4j:4.0.2")
    implementation("org.flywaydb:flyway-core:11.3.4")
    implementation("org.flywaydb:flyway-database-postgresql:11.3.4")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.github.kagkarlsson:db-scheduler:16.12.0")
    implementation("org.jetbrains.exposed:exposed-core:0.59.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.59.0")
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    implementation("com.github.librepdf:openpdf:2.4.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.0.2")
    implementation("de.mkammerer:argon2-jvm:2.11")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.1.3")
    testImplementation("com.lemonappdev:konsist:0.18.1")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin { jvmToolchain(21) }

kover {
    reports {
        filters {
            includes {
                classes("rw.rutongo.mmems.modules.status.StatusService", "rw.rutongo.mmems.modules.status.domain.*")
            }
        }
        verify {
            rule("Sprint 3 status coverage") {
                minBound(95, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
            }
        }
    }
}

tasks.test { useJUnitPlatform() }
