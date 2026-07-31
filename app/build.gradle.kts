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

    testOptions {
        // BookIndex is pure java.io, but it lives inside an object that imports
        // android.content.Context. Default-values keeps the stubbed android.jar
        // from throwing on class init instead of failing a real assertion.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Intentionally zero AndroidX / Material deps in the APK: plain Android Views
    // only, smallest APK, best D-pad behaviour, trivially 32-bit safe.
    // junit is testImplementation — it is not packaged and does not reach the phone.
    testImplementation("junit:junit:4.13.2")
}
