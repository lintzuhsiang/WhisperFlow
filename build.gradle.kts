// Single-project build file – this directory is the Gradle project root AND the
// only Android application module.  Because there is no separate root-level
// build.gradle.kts to declare plugin versions, the version numbers must be
// specified here directly (no "apply false" needed).
plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace  = "com.liveTranslation"
    compileSdk = 35

    defaultConfig {
        applicationId   = "com.liveTranslation"
        minSdk          = 29          // AudioPlaybackCaptureConfiguration requires API 29+
        targetSdk       = 35
        versionCode     = 1
        versionName     = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-DNDEBUG")
                arguments += listOf(
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF"
                )
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"   // accepts any CMake ≥ 3.22.1 that is installed
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    ndkVersion = "26.1.10909125"

    androidResources {
        // Do NOT compress binary model files – they are already non-compressible
        // and AAPT2 would waste time + RAM trying. ".onnx_data" entries can also
        // exceed the default ZIP-entry limit when compressed.
        noCompress += listOf("bin", "onnx", "tflite", "ort", "onnx_data")

        // Exclude the two NLLB decoder data blobs (2.7 GB + 2.6 GB) that exceed
        // AAPT2's per-entry limit.  The encoder (1.5 GB) is kept so basic
        // translation still works (encoder-only / greedy path in LocalNllbEngine).
        //
        // To test full translation, push the files to external storage manually:
        //   adb push nllb_onnx_output/decoder_model.onnx_data \
        //            /sdcard/Android/data/com.liveTranslation/files/
        //   adb push nllb_onnx_output/decoder_with_past_model.onnx_data \
        //            /sdcard/Android/data/com.liveTranslation/files/
        //
        // Also exclude ggml-medium.bin (1.4 GB) – not used in this build.
        // Switch WhisperCppEngine.MODEL_FILENAME back to "ggml-medium.bin" if needed.
        // Only the two NLLB decoder data blobs are excluded — they are 2.6–2.7 GB
        // each and exceed AAPT2's per-entry limit.  Everything else (including
        // ggml-medium.bin at 1.4 GB and encoder_model.onnx at 1.5 GB) is kept
        // and stored uncompressed thanks to noCompress above.
        ignoreAssetsPattern =
            "!decoder_model.onnx_data:!decoder_with_past_model.onnx_data"
    }
}

// NOTE: Do NOT add kotlin { jvmToolchain(17) } here.
// That activates Gradle's Java Toolchain Provisioning which tries to locate a
// standalone JDK 17 on the OS – it fails when none is registered.
// AGP handles Java/Kotlin version targeting through compileOptions + kotlinOptions
// inside android { } using Android Studio's bundled JDK.  No extra block needed.

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // SentencePiece JNI – JitPack artifact is unreliable; re-enable if a
    // stable Maven coordinate becomes available.
    // implementation("com.github.google:sentencepiece-jni:0.1.99")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
