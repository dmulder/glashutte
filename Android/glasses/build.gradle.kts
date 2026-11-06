plugins {
    // Kotlin support is built into AGP 9 — no separate Kotlin plugin needed.
    alias(libs.plugins.android.library)
}

// ---------------------------------------------------------------------------------------
// The proprietary VITURE Android SDK is NEVER part of this repository. It is supplied by
// whoever builds the app, and this module builds with or without it:
//
//   * SDK found   -> the JNI bridge is compiled and linked against it, its .so files are
//                    packaged, and `VitureGlassesBackend` (src/sdkPresent) is compiled in.
//   * SDK missing -> no native build at all; the stub backend (src/sdkAbsent) is used and
//                    the app runs without head tracking.
//
// Where it is looked up, first match wins:
//   1. -Pviture.sdk.dir=/path/to/viture-android-sdk      (or in ~/.gradle/gradle.properties)
//   2. the VITURE_SDK_DIR environment variable
//   3. <repo>/Android/libs/viture-sdk/                   (git-ignored)
// Pass -Pviture.sdk=off to force the stub even if an SDK is present.
//
// Expected layout (as shipped by VITURE):
//   <sdk>/libs/arm64-v8a/{libglasses.so, libcarina_vio.so, libcloud_protocol.so}
//   <sdk>/include/viture_*.h
// ---------------------------------------------------------------------------------------
val vitureSdkDir: File? = run {
    if (providers.gradleProperty("viture.sdk").orNull.equals("off", ignoreCase = true)) {
        return@run null
    }
    val configured = providers.gradleProperty("viture.sdk.dir").orNull
        ?: providers.environmentVariable("VITURE_SDK_DIR").orNull
    val dir = if (configured != null) rootProject.file(configured) else rootProject.file("libs/viture-sdk")

    // fileContents() makes Gradle's configuration cache notice when the SDK appears or goes away.
    val marker = providers.fileContents(
        layout.file(provider { File(dir, "include/viture_glasses_provider.h") }),
    ).asText
    if (!marker.isPresent) {
        if (configured != null) {
            logger.warn("viture.sdk.dir='$configured' has no include/viture_glasses_provider.h — building with the stub backend")
        }
        return@run null
    }
    val lib = File(dir, "libs/arm64-v8a/libglasses.so")
    if (!lib.isFile) {
        throw GradleException(
            "VITURE SDK headers found in $dir but libs/arm64-v8a/libglasses.so is missing. " +
                "Fix the SDK directory or pass -Pviture.sdk=off to build without it.",
        )
    }
    dir
}
val hasVitureSdk = vitureSdkDir != null
logger.lifecycle(
    if (hasVitureSdk) ":glasses: VITURE SDK found at $vitureSdkDir — building the SDK-backed backend"
    else ":glasses: VITURE SDK not supplied — building with the stub glasses backend",
)

android {
    namespace = "io.github.dmulder.glashutte.glasses"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        // The VITURE SDK ships arm64 binaries; this phone is arm64.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Stub backend logs through android.util.Log; return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    sourceSets.getByName("main") {
        kotlin.directories.add(if (hasVitureSdk) "src/sdkPresent/kotlin" else "src/sdkAbsent/kotlin")
        if (hasVitureSdk) {
            jniLibs.srcDir(File(vitureSdkDir, "libs"))
        }
    }

    if (hasVitureSdk) {
        ndkVersion = "30.0.14904198"
        defaultConfig {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += "-DVITURE_SDK_DIR=${vitureSdkDir!!.absolutePath}"
                }
            }
        }
        // Builds libglasses_bridge.so — the JNI bridge to the native VITURE SDK.
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "4.1.2"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
