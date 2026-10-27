# Shrinking rules for the instrumentation-test APK when tests run against the release build (-Pxander.testRelease).
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.**
-dontwarn org.junit.**
-dontwarn org.hamcrest.**
-keep class com.xander.files.NsfwOnDeviceTest { *; }
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn androidx.concurrent.futures.SuspendToFutureAdapter
-dontwarn com.google.auto.value.AutoValue