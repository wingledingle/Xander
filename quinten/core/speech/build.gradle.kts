plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // The ONNX Runtime Java API is the same on desktop and Android: the app supplies
    // onnxruntime-android, tests the desktop build.
    compileOnly(qlibs.onnxruntime)
    testImplementation(qlibs.onnxruntime)
    testImplementation(qlibs.junit)
}
