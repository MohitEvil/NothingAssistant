# sherpa-onnx (neural voice): its Kotlin classes are bound to native code and reached by reflection.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# commons-compress mentions optional compression libraries we don't use.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.annotation.**

# The native engine calls the audio callback by method name, so it must keep its exact shape.
-keep class com.demon.nothingassistant.audio.SamplesCallback { *; }
-keep class kotlin.jvm.functions.Function1 { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keep class moe.shizuku.** { *; }
-dontwarn moe.shizuku.**

# Vosk speech recognition talks to its native library through JNA.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
