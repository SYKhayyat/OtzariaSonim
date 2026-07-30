plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.otzaria.sonim"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.otzaria.sonim"
        minSdk = 24          // Android 7.0 — Sonim XP5s
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Intentionally zero AndroidX / Material deps: plain Android Views only,
    // smallest APK, best D-pad behaviour, trivially 32-bit safe. org.json is
    // part of the Android platform (android.jar) — no dependency needed.
}
