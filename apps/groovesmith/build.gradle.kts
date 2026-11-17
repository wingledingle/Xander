// Groovesmith Studio as its own app (dev.groovesmith.studio). The Studio page (modules-web/www/modules/studio) and
// the native host (:modules-host) are the same ones Xander's all-in-one build uses.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val buildInfo = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val buildNumber = buildInfo.getProperty("build").toInt()
base.archivesName.set("groovesmith-build-$buildNumber")

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String): String =
    (localProps.getProperty(key) ?: providers.gradleProperty(key).orNull ?: System.getenv(key.replace('.', '_').uppercase()) ?: "")

android {
    // Release signing with your upload key, from local.properties (never committed) or the environment:
    //   xander.keystore=/path/upload.jks  xander.keystorePassword=...  xander.keyAlias=...  xander.keyPassword=...
    // Without it, release builds are signed with the debug key: fine for sideloading, not accepted by Play.
    signingConfigs {
        if (cfg("xander.keystore").isNotBlank()) create("upload") {
            storeFile = file(cfg("xander.keystore")); storePassword = cfg("xander.keystorePassword")
            keyAlias = cfg("xander.keyAlias"); keyPassword = cfg("xander.keyPassword")
        }
    }
    namespace = "dev.groovesmith.studio"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.groovesmith.studio"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "${buildInfo.getProperty("version")} build $buildNumber"
    }
    // Only the Studio and the shared bridge script; the other web modules belong to Xander.
    sourceSets["main"].assets.srcDir(rootProject.file("modules-web/www"))
    androidResources { ignoreAssetsPatterns += listOf("model3d", "quicklook") }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/") }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":modules-host"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
}
