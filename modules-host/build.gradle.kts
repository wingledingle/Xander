// The host for web modules (page bridge, file streaming, native video with FX, low-latency pads, audio decoding),
// shared by Xander and the standalone Groovesmith app. The app supplies storage through [HostStorage].
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xander.moduleshost"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint { abortOnError = false }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.webkit)
    api(libs.media3.exoplayer)
    api(libs.media3.datasource)
    implementation(libs.media3.effect)
    implementation(libs.media3.transformer)
}
