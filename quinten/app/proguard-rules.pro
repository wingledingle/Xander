# PdfBox-Android and Tesseract use reflection/JNI.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-keep class com.googlecode.tesseract.** { *; }
-keep class com.googlecode.leptonica.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
-dontwarn javax.annotation.**
-dontwarn org.jsoup.**
