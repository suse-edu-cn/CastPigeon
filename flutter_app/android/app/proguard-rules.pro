# Keep Android framework entry points that are created by class name from merged manifests.
-keep class com.suseoaa.castpigeon.CastPigeonApp { *; }
-keep class com.suseoaa.castpigeon.flutter.MainActivity { *; }
-keep class com.suseoaa.castpigeon.ui.TransparentClipboardActivity { *; }
-keep class com.suseoaa.castpigeon.service.BleForegroundService { *; }
-keep class com.suseoaa.castpigeon.service.MyNotificationListener { *; }

# Shizuku UserService is started by Shizuku using stable class names and constructors.
-keep class com.suseoaa.castpigeon.service.ShizukuClipboardService { *; }
-keep class com.suseoaa.castpigeon.service.ShizukuClipboardService$* { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# AIDL Binder interfaces cross process boundaries and must keep their generated Stub shape.
-keep interface com.suseoaa.castpigeon.IRootClipboard { *; }
-keep class com.suseoaa.castpigeon.IRootClipboard$* { *; }
-keep interface com.suseoaa.castpigeon.IClipboardChangeCallback { *; }
-keep class com.suseoaa.castpigeon.IClipboardChangeCallback$* { *; }

# kotlinx.serialization powers update metadata and BLE/LAN payloads.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class **$$serializer { *; }
-keep class **$$serializer { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**
