// Quinten Reader as its own app (app.quinten). The reader itself is the :quinten:app library, shared unchanged
// with Xander's all-in-one build; this module only adds the launcher entry, "Open with" and app settings.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val buildInfo = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val buildNumber = buildInfo.getProperty("build").toInt()
base.archivesName.set("quinten-build-$buildNumber")

android {
    namespace = "app.quinten.standalone"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.quinten"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "${buildInfo.getProperty("version")} build $buildNumber"
        val abi = providers.gradleProperty("xander.abi").orNull
        ndk { abiFilters += abi?.split(',') ?: listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf("META-INF/*.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "org/bouncycastle/pqc/**")
        }
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":quinten:app"))
}
