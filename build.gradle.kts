plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("plugin.serialization") version "2.1.20" apply false
    id("io.ktor.plugin") version "3.1.3" apply false
}

allprojects { group = "rw.rutongo.mmems"; version = "0.1.0-SNAPSHOT" }
