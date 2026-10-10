plugins {
    id("com.android.application")
}

android {
    namespace = "com.kaagaz.app"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.kaagaz.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-mission-os"
        // Phones only: drops the x86_64 copies of the LiteRT-LM and ML Kit native libraries.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // On-device LLM runtime for Gemma (.litertlm models). Model weights are NOT bundled; the user imports them.
    // 0.16.1 is the newest release built with Kotlin 2.2 (0.17+ needs a Kotlin 2.4 compiler; AGP 9.0.1 bundles 2.2).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    testImplementation("junit:junit:4.13.2")
}
