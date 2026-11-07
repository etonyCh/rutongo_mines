plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("app.cash.sqldelight")
    id("org.jetbrains.kotlinx.kover")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "rw.rutongo.mmems.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "rw.rutongo.mmems"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release { isMinifyEnabled = false }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        animationsDisabled = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
}

sqldelight {
    databases {
        create("MmemsDatabase") {
            packageName.set("rw.rutongo.mmems.android.db")
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-okhttp:3.1.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    implementation("io.ktor:ktor-client-logging:3.1.3")
    implementation("app.cash.sqldelight:android-driver:2.1.0")
    implementation("app.cash.sqldelight:coroutines-extensions:2.1.0")
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.5.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("app.cash.sqldelight:sqlite-driver:2.1.0")
    testImplementation("io.ktor:ktor-client-mock:3.1.3")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.03.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
