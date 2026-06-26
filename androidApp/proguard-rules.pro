# 保留 Manifest 入口类，避免系统服务、Activity 和 Application 在混淆后无法被系统反射创建。
-keep class com.suseoaa.castpigeon.CastPigeonApp { *; }
-keep class com.suseoaa.castpigeon.MainActivity { *; }
-keep class com.suseoaa.castpigeon.ui.TransparentClipboardActivity { *; }
-keep class com.suseoaa.castpigeon.service.BleForegroundService { *; }
-keep class com.suseoaa.castpigeon.service.MyNotificationListener { *; }

# Shizuku UserService 由 Shizuku 框架按类名启动，构造函数和类名都需要稳定。
-keep class com.suseoaa.castpigeon.service.ShizukuClipboardService { *; }
-keep class com.suseoaa.castpigeon.service.ShizukuClipboardService$* { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# AIDL Binder 接口跨进程调用依赖 Stub 类名和方法签名，混淆会导致主进程与 Shizuku 服务无法通信。
-keep interface com.suseoaa.castpigeon.IRootClipboard { *; }
-keep class com.suseoaa.castpigeon.IRootClipboard$* { *; }
-keep interface com.suseoaa.castpigeon.IClipboardChangeCallback { *; }
-keep class com.suseoaa.castpigeon.IClipboardChangeCallback$* { *; }

# 更新检查和 BLE/LAN 协议都使用 kotlinx.serialization，保留序列化器和注解信息让 JSON 字段稳定。
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class **$$serializer { *; }
-keep class **$$serializer { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# libsu 负责 root 后端，保守保留其公开 API，避免不同 ROM 下反射/服务调用被裁剪。
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Compose、Markdown 和 Haze 在 release 下通常自带规则；这里仅忽略可选平台类警告，避免 CI 被无关警告打断。
-dontwarn androidx.compose.**
-dontwarn com.mikepenz.markdown.**
-dontwarn dev.chrisbanes.haze.**
