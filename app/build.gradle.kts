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

        // Large models are provided from external storage (adb push to:
        // /sdcard/Android/data/com.liveTranslation/files/...), so exclude them
        // from APK packaging to keep install size/time reasonable.
        //
        // What STAYS in the APK (small fallbacks):
        //   ggml-tiny.en.bin          ~74 MB   (fallback whisper model)
        //   sentencepiece.bpe.model   ~4.6 MB  (NLLB tokenizer)
        //
        // What is EXCLUDED (pushed via adb):
        //   ggml-medium.bin           ~1.4 GB  (whisper medium)
        //   nllb_onnx_output/         ~6.9 GB  (NLLB FP32 — 3 ONNX + data files)
        //   nllb_int8/                ~1.7 GB  (NLLB INT8 — 3 ONNX files)
        ignoreAssetsPattern =
            "!ggml-medium.bin:" +
            "!nllb_onnx_output:" +
            "!nllb_int8"
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

    // SentencePiece: built from C++ source via CMakeLists.txt (FetchContent)
    // and exposed through sentencepiece_jni.cpp → SentencePieceJni.kt.
    // No external Maven/JitPack dependency needed.

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Media3 ExoPlayer — used by VideoTranslationActivity (dual-track player)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")   // HLS (.m3u8) support
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")  // DASH (.mpd) support
    implementation("androidx.media3:media3-ui:1.3.1")
}
