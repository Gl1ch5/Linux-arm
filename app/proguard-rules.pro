# The X server is constructed reflectively; native code calls back into it by name.
-keep class com.termux.x11.CmdEntryPoint { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *** serializer(...); }
# zstd-jni uses JNI callbacks.
-keep class com.github.luben.zstd.** { *; }
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
