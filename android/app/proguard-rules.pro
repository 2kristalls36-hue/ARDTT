# JSch (SSH deploy) — reflection on host-key / cipher names
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# AmneziaWG JNI (libwg-go.so)
-keep class org.amnezia.awg.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# OkHttp / platform
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# EncryptedSharedPreferences / Tink
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ZXing camera scan
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# Kotlin
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-dontwarn kotlin.**
-dontwarn kotlinx.**
