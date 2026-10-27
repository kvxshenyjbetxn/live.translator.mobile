import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releasePropertiesFile = rootProject.file(".signing/release.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.isFile) releasePropertiesFile.inputStream().use { load(it) }
}
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

android {
    namespace = "dev.live.translator.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.live.translator.mobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            releaseProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }?.let {
                storeFile = rootProject.file(it)
            }
            storePassword = releaseProperties.getProperty("storePassword")
            keyAlias = releaseProperties.getProperty("keyAlias")
            keyPassword = releaseProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
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

// Keep debug builds usable without release credentials, but never assemble an unsigned release.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(releasePropertiesFile.isFile && releaseSigningKeys.all {
            !releaseProperties.getProperty(it).isNullOrBlank()
        }) { "Release signing requires .signing/release.properties with storeFile, storePassword, keyAlias and keyPassword." }
        check(rootProject.file(releaseProperties.getProperty("storeFile")).isFile) {
            "Release signing storeFile does not exist (path is relative to the project root)."
        }
    }
}
