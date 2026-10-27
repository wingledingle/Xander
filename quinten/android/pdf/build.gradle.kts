plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")

}

android {
    namespace = "app.quinten.pdf"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":quinten:core:analysis"))
    implementation(qlibs.pdfbox.android)
    implementation(qlibs.kotlinx.coroutines.android)
    testImplementation(qlibs.junit)
    testImplementation(qlibs.robolectric)
    testImplementation(qlibs.androidx.test.core)
    testImplementation(qlibs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // Robolectric downloads android-all jars at runtime; use the Maven Central mirror.
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
    maxHeapSize = "3g"
}
