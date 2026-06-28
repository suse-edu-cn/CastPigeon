# CastPigeon

CastPigeon 是 Android 与 macOS 之间的近场配对、消息同步和局域网文件传输应用。当前主线已经迁移为混合架构：Flutter 负责界面，Kotlin Multiplatform 负责共享逻辑与平台运行时。

## 架构

```text
.
├── flutter_app/      # Flutter 应用入口，包含 Android 与 macOS 平台壳
├── androidRuntime/   # Android 原生能力层：BLE、通知、剪贴板、权限、更新、文件传输
├── sharedLogic/      # Android/KMP 共享协议、状态机、BLE 抽象、UDP 与序列化模型
├── kmp_core/         # macOS Flutter 使用的 KMP Native/FFI 核心动态库
├── gradle/           # 根 Gradle 版本目录与 wrapper
└── .github/          # 发布 CI
```

UI 层只能通过 `CastPigeonApi` 抽象访问底层能力。Android 运行时通过 Flutter MethodChannel/EventChannel 接入 `androidRuntime`；macOS 运行时保留原生 CoreBluetooth/LAN 逻辑并由 Flutter macOS Runner 桥接，同时复制 `kmp_core` 产出的 `libcastpigeon_core.dylib`。

## 正确打开方式

日常写代码建议直接用 Android Studio 或 IntelliJ 打开仓库根目录 `/Users/vincent/Desktop/CastPigeon`，这样 `flutter_app`、`androidRuntime`、`sharedLogic`、`kmp_core` 都在同一个工作区里。

运行 Flutter 应用时进入 `flutter_app`：

```bash
cd flutter_app
flutter pub get
flutter run -d macos
flutter run -d <android-device-id>
```

Android Studio 如果没有显示 macOS 设备，先确认 Flutter 插件已启用，并在 Terminal 里运行 `flutter devices`。本仓库的可运行入口是 `flutter_app/lib/main.dart`，不是根目录旧原生 Android App。

## 常用验证

```bash
./gradlew projects
./gradlew :sharedLogic:compileKotlinMacosArm64 :kmp_core:linkDebugSharedMacosArm64
cd flutter_app && flutter analyze
cd flutter_app && flutter test
cd flutter_app && flutter build apk --debug
cd flutter_app && flutter build macos --debug
```

## 发布打包

版本号统一来自 `flutter_app/pubspec.yaml`：

```yaml
version: 1.0.10+110
```

Android 正式 APK 使用 `flutter_app/android/app/build.gradle.kts` 的 release signing 配置。CI 需要以下 GitHub Secrets：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

本地如果存在 `/Users/vincent/Desktop/SUSE-APP-Key/APP-Key.jks`，也可以直接执行：

```bash
cd flutter_app
flutter build apk --release
```

macOS CI 使用 Xcode ad-hoc 签名构建 arm64 DMG，产物命名保持更新器兼容：

```text
CastPigeon-macOS-v<version>.dmg
CastPigeon-Android-v<version>.apk
```

## CI

`.github/workflows/release.yml` 会在 `main` 或 `master` 上的 Flutter/KMP/runtime 相关文件变化时触发。流程会：

1. 从 `flutter_app/pubspec.yaml` 读取版本。
2. 如果 `v<version>` 标签不存在且版本高于当前最新标签，构建发布。
3. 生成正式签名 Android APK。
4. 生成 ad-hoc macOS DMG。
5. 生成 changelog 并发布 GitHub Release。
