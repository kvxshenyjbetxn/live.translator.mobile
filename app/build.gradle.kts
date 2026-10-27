plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.live.translator.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.live.translator.mobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
