# Same keep rules Xander's build applies to the libraries Quinten uses. Without them the shrinker renamed Bouncy
# Castle, which PdfBox-Android loads by class name (org.bouncycastle.jce.provider.BouncyCastleProvider) to decrypt
# PDFs: most PDFs carry empty-password encryption, so their text came out garbled. ONNX Runtime (read-aloud) is
# called from native code by name.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
