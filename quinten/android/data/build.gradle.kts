plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.quinten.data"
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
    api(project(":quinten:core:formats"))
    implementation(qlibs.androidx.room.runtime)
    implementation(qlibs.androidx.room.ktx)
    ksp(qlibs.androidx.room.compiler)
    implementation(qlibs.androidx.documentfile)
    implementation(qlibs.kotlinx.serialization.json)
    testImplementation(qlibs.androidx.room.testing)
    implementation(qlibs.kotlinx.coroutines.android)
    testImplementation(qlibs.junit)
    testImplementation(qlibs.robolectric)
    testImplementation(qlibs.androidx.test.core)
    testImplementation(qlibs.kotlinx.coroutines.test)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

tasks.withType<Test>().configureEach {
    // Robolectric downloads android-all jars at runtime; use the Maven Central mirror.
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
    maxHeapSize = "3g"
}
