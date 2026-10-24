import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String): String =
    (localProps.getProperty(key) ?: providers.gradleProperty(key).orNull ?: System.getenv(key.replace('.', '_').uppercase()) ?: "")

android {
    namespace = "com.xander.files"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xander.files"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "com.xander.files.HiltTestRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${cfg("xander.googleWebClientId")}\"")
        buildConfigField("String", "DROPBOX_APP_KEY", "\"${cfg("xander.dropboxAppKey")}\"")
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"${cfg("xander.onedriveClientId")}\"")
        buildConfigField("String", "BOX_CLIENT_ID", "\"${cfg("xander.boxClientId")}\"")
        buildConfigField("String", "BOX_CLIENT_SECRET", "\"${cfg("xander.boxClientSecret")}\"")
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"${cfg("xander.yandexClientId")}\"")
        manifestPlaceholders["appAuthRedirectScheme"] = "com.xander.files"
    }

    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
            buildConfigField("boolean", "IS_TV_BUILD", "false")
        }
        create("tv") {
            dimension = "device"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
            buildConfigField("boolean", "IS_TV_BUILD", "true")
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ADS_ENABLED", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ADS_ENABLED", cfg("xander.adsEnabled").ifBlank { "false" })
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/io.netty.versions.properties", "META-INF/BCKEY.*", "META-INF/BC*.SF", "META-INF/BC*.DSA"
            )
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-Xannotation-default-target=param-property"
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.splashscreen)
    implementation(libs.window)
    implementation(libs.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.media3.cast)
    implementation(libs.cast.framework)
    implementation(libs.mediarouter)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)

    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.smbj)
    implementation(libs.jcifs)
    implementation(libs.commons.net)
    implementation(libs.sshj)
    implementation(libs.bouncycastle)
    implementation(libs.nfs.client)
    implementation(libs.commons.compress)
    implementation(libs.zip4j)
    implementation(libs.xz)
    implementation(libs.junrar)
    implementation(libs.libsu.core)
    implementation(libs.libsu.io)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.nanohttpd)
    implementation(libs.appauth)
    implementation(libs.play.auth)
    implementation(libs.billing)
    implementation(libs.biometric)
    implementation(libs.leanback)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.coroutines.test)
}
