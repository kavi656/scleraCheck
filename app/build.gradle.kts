plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.scleracheck"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scleracheck"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-hackathon"
    }

    buildTypes {
        debug {
            // Developer toggle default: debug builds start with dev tools available but OFF.
            buildConfigField("boolean", "DEV_TOOLS_AVAILABLE", "true")
        }
        release {
            // Demo build: developer toggle (patch saving, live overlay numbers) hidden entirely.
            isMinifyEnabled = false
            buildConfigField("boolean", "DEV_TOOLS_AVAILABLE", "false")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The MediaPipe .task model must not be compressed so it can be memory-mapped.
        noCompress += "task"
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // CameraX + Camera2Interop (manual AE/AWB/AF controls)
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // MediaPipe Tasks Vision (Face Landmarker with iris landmarks)
    implementation("com.google.mediapipe:tasks-vision:0.10.20")

    // Room (baseline + check history)
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    testImplementation("junit:junit:4.13.2")
}
