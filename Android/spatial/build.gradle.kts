plugins {
    // Kotlin support is built into AGP 9 — no separate Kotlin plugin needed.
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.dmulder.glashutte.spatial"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Head tracking feeds the camera through an onPose callback.
    implementation(project(":glasses"))
}
