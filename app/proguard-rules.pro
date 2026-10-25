-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-dontwarn javax.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.**
-dontwarn sun.**
-dontwarn java.awt.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn net.i2p.crypto.**
-dontwarn org.newsclub.**
-dontwarn com.jcraft.**
-dontwarn org.apache.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn kotlinx.serialization.**
-keep class com.hierynomus.** { *; }
-keep class net.schmizz.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.apache.commons.net.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class com.emc.ecs.nfsclient.** { *; }
-keep class net.lingala.zip4j.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class com.xander.files.data.shizuku.** { *; }
-keep class com.xander.files.**$$serializer { *; }
-keepclassmembers class com.xander.files.** { *** Companion; }
-keepclasseswithmembers class com.xander.files.** { kotlinx.serialization.KSerializer serializer(...); }

# Referenced only from manifest <meta-data> (OPTIONS_PROVIDER_CLASS_NAME); R8 cannot see it.
-keep class com.xander.files.data.cast.CastOptionsProvider { <init>(); }
# smbj's event bus (MBassador) dispatches to @Handler methods by reflection.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * { @net.engio.mbassy.listener.Handler *; }
# sshj loads Ed25519 support through the EdDSA JCA provider.
-keep class net.i2p.crypto.eddsa.** { *; }
# nfs-client uses Netty channel handlers created reflectively.
-keep class io.netty.** { *; }
-dontwarn io.netty.**
