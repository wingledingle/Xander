plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// One Bouncy Castle for the whole APK: PdfBox-Android (Quinten) asks for the jdk15to18 build, Xander uses jdk18on.
subprojects {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            for (m in listOf("bcprov", "bcpkix", "bcutil")) {
                substitute(module("org.bouncycastle:$m-jdk15to18")).using(module("org.bouncycastle:$m-jdk18on:1.81"))
            }
        }
    }
}
