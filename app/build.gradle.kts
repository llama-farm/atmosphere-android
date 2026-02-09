plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

// Check for local llama.cpp AAR
val llamaAarFile = file("libs/llama-android.aar")
val hasLlamaAar = llamaAarFile.exists()

android {
    namespace = "com.llamafarm.atmosphere"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.llamafarm.atmosphere"
        minSdk = 33  // Required by llama.cpp AAR
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        vectorDrawables {
            useSupportLibrary = true
        }

        // NDK configuration for Rust libraries
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        // Build config fields
        buildConfigField("String", "LLAMA_MODEL_PATH", "\"models/\"")
    }

    signingConfigs {
        create("release") {
            // Load from local.properties or environment variables
            val keystoreFile = findProperty("ATMOSPHERE_KEYSTORE_FILE") as String?
                ?: System.getenv("ATMOSPHERE_KEYSTORE_FILE")
            val keystorePassword = findProperty("ATMOSPHERE_KEYSTORE_PASSWORD") as String?
                ?: System.getenv("ATMOSPHERE_KEYSTORE_PASSWORD")
            val keyAlias = findProperty("ATMOSPHERE_KEY_ALIAS") as String?
                ?: System.getenv("ATMOSPHERE_KEY_ALIAS")
            val keyPassword = findProperty("ATMOSPHERE_KEY_PASSWORD") as String?
                ?: System.getenv("ATMOSPHERE_KEY_PASSWORD")

            if (keystoreFile != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Only sign if config is available
            val releaseSigningConfig = signingConfigs.findByName("release")
            if (releaseSigningConfig?.storeFile?.exists() == true) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
        // Include Rust .so libraries
        jniLibs {
            useLegacyPackaging = false
        }
    }
    
    // Don't compress large model files - they're already compressed
    androidResources {
        noCompress += listOf("gguf", "bin", "model", "onnx")
    }

    sourceSets {
        getByName("main") {
            // Include Rust-built native libraries and llama.cpp libraries
            jniLibs.srcDirs("src/main/jniLibs", "../rust/target/jniLibs", "libs/jniLibs")
        }
    }
}

dependencies {
    // Local llama.cpp AAR (if available)
    // To build: clone llama.cpp, run ./gradlew :examples:llama.android:lib:assembleRelease
    // Copy the AAR to app/libs/llama-android.aar
    // See app/libs/README.md for detailed instructions
    if (hasLlamaAar) {
        implementation(files("libs/llama-android.aar"))
    }
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // WorkManager for background processing
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // DocumentFile for file/folder picking
    implementation("androidx.documentfile:documentfile:1.0.1")
    
    // CameraX for QR scanning
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    
    // ML Kit Barcode Scanning for QR codes
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    
    // OkHttp for WebSocket mesh connection
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // ONNX Runtime for vision models
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")
    
    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
