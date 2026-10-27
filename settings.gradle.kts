pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        maven("https://jitpack.io")
    }
    // Quinten (quinten/) keeps its own library list, aligned with Xander's versions.
    versionCatalogs { create("qlibs") { from(files("quinten/libs.versions.toml")) } }
}
rootProject.name = "XanderFiles"
include(":app")

// Quinten e-reader (vendored from wingledingle/Quinten): its modules build as part of Xander.
for (m in listOf("core:model", "core:formats", "core:analysis", "core:speech", "android:data", "android:render", "android:pdf", "android:ocr", "app")) {
    include(":quinten:$m")
    project(":quinten:$m").projectDir = file("quinten/${m.replace(':', '/')}")
}
