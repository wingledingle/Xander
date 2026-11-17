// Oleander GBA emulator as its own app (dev.oleander.app). Code and web app come from third_party/oleander,
// shared with Xander's all-in-one build; the game list comes from Oleander's own folder scan here.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val buildInfo = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val buildNumber = buildInfo.getProperty("build").toInt()
base.archivesName.set("oleander-build-$buildNumber")

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
    namespace = "dev.oleander.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.oleander.app"
        minSdk = 24
        targetSdk = 36
        versionCode = buildNumber
        versionName = "${buildInfo.getProperty("version")} build $buildNumber"
    }
    sourceSets["main"].java.srcDir(rootProject.file("third_party/oleander/java"))
    sourceSets["main"].assets.srcDir(rootProject.file("third_party/oleander/assets"))
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
    androidResources { noCompress += "wasm" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation(libs.webkit)
}
