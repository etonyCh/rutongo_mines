plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("io.ktor.plugin") version "3.1.3"
    application
}

application { mainClass.set("rw.rutongo.mmems.ApplicationKt") }

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-core-jvm:3.1.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.1.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.1.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.1.3")
    implementation("io.ktor:ktor-server-rate-limit-jvm:3.1.3")
    implementation("io.insert-koin:koin-ktor:4.0.2")
    implementation("io.insert-koin:koin-logger-slf4j:4.0.2")
    implementation("org.flywaydb:flyway-core:11.3.4")
    implementation("org.flywaydb:flyway-database-postgresql:11.3.4")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.jetbrains.exposed:exposed-core:0.59.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.59.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.0.2")
    implementation("de.mkammerer:argon2-jvm:2.11")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launch")
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
