plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.quinten"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Built as a library inside Xander: the app module controls signing, shrinking and ABIs.
        consumerProguardFiles("proguard-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += setOf("META-INF/*.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
            // Post-quantum parameter tables of Bouncy Castle (~8 MB). PdfBox-Android only uses the
            // classic CMS/PKCS classes for encrypted PDFs and never touches org.bouncycastle.pqc.
            excludes += "org/bouncycastle/pqc/**"
        }
    }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":quinten:android:data"))
    implementation(project(":quinten:android:render"))
    implementation(project(":quinten:android:pdf"))
    implementation(project(":quinten:android:ocr"))
    implementation(project(":quinten:core:speech"))
    implementation(qlibs.onnxruntime.android)
    implementation(qlibs.androidx.core.ktx)
    implementation(qlibs.androidx.activity.compose)
    implementation(qlibs.androidx.lifecycle.runtime)
    implementation(qlibs.androidx.lifecycle.runtime.compose)
    implementation(qlibs.androidx.lifecycle.viewmodel.compose)
    implementation(qlibs.androidx.lifecycle.service)
    implementation(qlibs.androidx.navigation.compose)
    implementation(platform(qlibs.androidx.compose.bom))
    implementation(qlibs.androidx.compose.ui)
    implementation(qlibs.androidx.compose.material3)
    implementation(qlibs.androidx.compose.material.icons)
    implementation(qlibs.androidx.compose.ui.tooling.preview)
    implementation(qlibs.androidx.datastore.preferences)
    implementation(qlibs.androidx.documentfile)
    implementation(qlibs.kotlinx.coroutines.android)
    debugImplementation(qlibs.androidx.compose.ui.tooling)
    debugImplementation(qlibs.androidx.compose.ui.test.manifest)
    testImplementation(qlibs.junit)
    testImplementation(qlibs.robolectric)
    testImplementation(qlibs.androidx.test.core)
    testImplementation(qlibs.kotlinx.coroutines.test)
    testImplementation(platform(qlibs.androidx.compose.bom))
    testImplementation(qlibs.androidx.compose.ui.test.junit4)
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
    maxHeapSize = "3g"
}
