<div align="center">

# 🕊️ CastPigeon (投鸽)

**让 Android 与 macOS 像一台设备一样协作。**

[![Flutter UI](https://img.shields.io/badge/Flutter-UI-02569B?logo=flutter&logoColor=white&style=for-the-badge)](#)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white&style=for-the-badge)](#)
[![Gradle](https://img.shields.io/badge/Gradle-9.1.0-02303A?logo=gradle&logoColor=white&style=for-the-badge)](#)
[![Android](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white&style=for-the-badge)](#)
[![macOS](https://img.shields.io/badge/macOS-Native-000000?logo=apple&logoColor=white&style=for-the-badge)](#)

<p><i>近场发现、可信配对、通知同步、剪贴板同步、局域网文件流转。</i></p>

</div>

---

## 📖 项目简介

**CastPigeon (投鸽)** 是一款面向 Android 与 macOS 的近场设备协作工具。它通过 BLE、UDP 局域网发现和本地文件通道，在两端之间完成设备配对、通知同步、剪贴板同步和文件传输。

当前主线已经从原生双端 UI 迁移为 **Flutter + Kotlin Multiplatform + 平台 runtime** 的混合架构：Flutter 负责完整界面体验，KMP 承担可共享的协议、状态机和 native core，Android/macOS runtime 负责连接、权限、系统能力与真实传输。

这次重构不是把旧能力推倒重来，而是把旧版的 BLE/UDP 混合发现、PIN 绑定、可信设备持久化、状态机驱动、桌面原生能力和移动端视觉探索，重新装进一个更清晰的分层里。

---

## 🧭 新架构思路

> Flutter 专心画 UI；KMP 和平台 runtime 专心做事情。

| 层级 | 职责 | 边界 |
| --- | --- | --- |
| 🎨 Flutter UI | Android 与 macOS 的主界面、设备管理、历史记录、更新页面 | 只消费 `CastPigeonApi`，不直接碰蓝牙、通知、剪贴板、文件系统或 FFI |
| 🔌 Bridge | `CastPigeonApi`、`AndroidCastApi`、`MacOSCastApi`、`FFICastApi` | 把 UI 动作转换成平台调用，把平台状态整理成 snapshot |
| 🧠 KMP 共享逻辑 | `sharedLogic` 与 `kmp_core` 中的协议、状态机、UDP 发现、序列化模型和 native exports | 只放可共享、可测试、可复用的核心逻辑 |
| 📱 Android runtime | 通知监听、Shizuku、前台服务、应用图标、剪贴板、BLE GATT、LAN 文件传输 | 负责 Android 系统能力和真实设备接入 |
| 🖥️ macOS runtime | CoreBluetooth、UserNotifications、NSPasteboard、LAN 文件接收、SQLite 历史记录 | 负责 macOS 原生能力；macOS 不走 Android/Shizuku 逻辑 |

默认运行路径已经是真实 bridge：`flutter_app/lib/main.dart` 会按平台创建 `AndroidCastApi` 或 `MacOSCastApi`，不会把 mock 当作主运行路径。

---

## 🧱 工程地图

```text
.
├── flutter_app/
│   ├── lib/main.dart                         # Flutter 入口，注入 CastPigeonApi
│   ├── lib/ui/                               # Android/macOS 共享 UI
│   ├── lib/core/bridge/cast_pigeon_api.dart  # UI 与底层能力之间的唯一契约
│   ├── lib/core/bridge/android_cast_api.dart # Android MethodChannel/EventChannel bridge
│   ├── lib/core/bridge/macos_cast_api.dart   # macOS MethodChannel/EventChannel bridge
│   ├── lib/core/bridge/ffi_cast_api.dart     # KMP native/FFI bridge
│   ├── android/                              # Flutter Android shell
│   └── macos/                                # Flutter macOS shell + Swift runtime bridge
├── androidRuntime/                           # Android 原生能力层
├── sharedLogic/                              # KMP shared logic，Android 复用的协议/状态/网络逻辑
├── kmp_core/                                 # KMP Native core，输出 libcastpigeon_core.dylib
├── gradle/                                   # Gradle wrapper、version catalog 与 JVM 配置
├── .github/workflows/release.yml             # Android APK + macOS DMG 发布流水线
└── 架构指南.md                                # 面向 AI 协作的架构约束草案
```

---

## 🔌 运行时分层

```text
Flutter UI
  |
  | CastPigeonApi
  |
  +-- AndroidCastApi
  |     |
  |     | MethodChannel / EventChannel
  |     |
  |     +-- androidRuntime
  |           +-- BLE foreground service
  |           +-- notification listener
  |           +-- clipboard and Shizuku backend
  |           +-- LAN file transfer
  |           +-- update manager
  |           +-- sharedLogic
  |
  +-- MacOSCastApi
  |     |
  |     | MethodChannel / EventChannel
  |     |
  |     +-- Flutter macOS Runner
  |           +-- CoreBluetooth runtime
  |           +-- Swift UDP discovery
  |           +-- LAN file receiver
  |           +-- NSPasteboard clipboard sync
  |           +-- UserNotifications
  |           +-- SQLite history database
  |           +-- kmp_core dylib copy step
  |
  +-- FFICastApi
        |
        +-- kmp_core native exports
```

`CastPigeonSnapshot` 是 UI 层的主要状态模型。底层 runtime 负责把连接状态、绑定设备、在线设备、历史消息、剪贴板记录、传输进度、更新状态和调试日志整理成 snapshot；Flutter 只负责订阅 snapshot 并渲染。

---

## ✨ 核心能力

| 能力 | 实现方式 |
| --- | --- |
| 🔍 BLE 与 UDP 混合发现 | BLE 负责近场发现和低功耗连接，UDP 局域网广播补足配对和能力信息传递 |
| 🔐 PIN 绑定与可信设备 | 两端通过 PIN 显式确认，绑定成功后持久化可信设备，后续自动恢复连接 |
| 🔔 通知同步 | Android 保留通知监听和前台服务，macOS 通过 UserNotifications 展示通知 |
| 📋 剪贴板同步 | Android runtime 与 NSPasteboard 分别接入系统剪贴板，并做去重窗口避免循环广播 |
| 📁 文件传输 | Flutter 展示选择入口和传输状态，实际 I/O 走平台 LAN 文件通道 |
| 🧾 历史记录 | 消息和剪贴板历史由平台侧数据库或 runtime 读取后进入 snapshot |
| 📝 Markdown 更新日志 | Android 和 macOS 的检查更新页面都支持 Markdown 渲染 GitHub Release 内容 |

---

## 🎨 UI 方向

旧版 Android 的视觉探索没有丢掉，只是从 Compose/SwiftUI 分散实现，收敛到了 Flutter 统一设计语言里：

- Android 保留移动端底部导航、加大圆角和页面左右滑动切换。
- macOS 使用桌面侧边栏，保留设备管理、消息历史、剪贴板历史、更新页面和系统级菜单图标。
- 移动端和桌面端共享同一套状态模型，但布局、交互密度和平台能力入口分开设计。
- UI 不再承载业务逻辑，只负责把 snapshot 画出来，把用户动作交给 bridge。

---

## 🛠️ 开发边界

新增功能时先判断它属于哪一层。

| 想改什么 | 应该改哪里 |
| --- | --- |
| 展示、布局、动画、文案 | `flutter_app/lib/ui` |
| UI 需要的新状态或动作 | `CastPigeonApi`、`CastPigeonSnapshot`、Android/macOS bridge |
| Android 系统能力 | `androidRuntime`，必要时把可共享协议放进 `sharedLogic` |
| macOS 系统能力 | `flutter_app/macos/Runner/MacOSRuntime` 和 `MacOSCastPigeonBridge.swift` |
| 跨平台纯逻辑或 native FFI | `kmp_core`，并保持 Dart FFI 的内存释放边界清晰 |

不要在 Flutter widget 中直接导入平台 API、`dart:ffi` 或系统能力库。UI 层只消费 `CastPigeonApi`。

---

## 💻 正确打开方式

日常写代码建议直接用 Android Studio 或 IntelliJ 打开仓库根目录：

```text
/Users/vincent/Desktop/CastPigeon
```

这样 `flutter_app`、`androidRuntime`、`sharedLogic`、`kmp_core` 会在同一个工作区里，改 UI 和改 native runtime 都方便。

运行 Flutter 应用时进入 `flutter_app`：

```bash
cd flutter_app
flutter pub get
flutter devices
flutter run -d macos
flutter run -d <android-device-id>
```

本仓库当前可运行入口是 `flutter_app/lib/main.dart`。根目录不再是旧原生 Android App 的运行入口。

---

## ✅ 常用验证

```bash
./gradlew projects
./gradlew :sharedLogic:compileKotlinMacosArm64 :kmp_core:linkDebugSharedMacosArm64

cd flutter_app
flutter analyze
flutter test
flutter build apk --debug
flutter build apk --release
flutter build macos --debug
```

macOS 本地如果遇到 Flutter Swift Package Manager 的实验性集成问题，可以先执行：

```bash
flutter config --no-enable-swift-package-manager
```

CI 会在 `xcodebuild` 前运行 `flutter build macos --release --config-only`，确保 `macos/Flutter/ephemeral` 下的 Flutter file list 已生成。

---

## 📦 版本、签名与发布

版本号统一来自 `flutter_app/pubspec.yaml`：

```yaml
version: 1.0.10+110
```

这个值会同步影响：

- Android `versionName` / `versionCode`
- macOS `CFBundleShortVersionString` / `CFBundleVersion`
- CI 生成的 GitHub Release 标签 `v<version>`

Android debug 和 release 都使用正式签名配置。CI 需要以下 GitHub Secrets：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

本地如果存在 `/Users/vincent/Desktop/SUSE-APP-Key/APP-Key.jks`，可以直接构建正式签名 APK：

```bash
cd flutter_app
flutter build apk --debug
flutter build apk --release
```

macOS CI 使用 ad-hoc 签名构建 arm64 DMG。发布产物命名保持更新器兼容：

```text
CastPigeon-Android-v<version>.apk
CastPigeon-Android-v<version>.apk.sha256
CastPigeon-macOS-v<version>.dmg
CastPigeon-macOS-v<version>.dmg.sha256
```

---

## 🚦 CI 流程

`.github/workflows/release.yml` 会在 `main` 或 `master` 上的 Flutter/KMP/runtime 相关文件变化时触发。

1. 从 `flutter_app/pubspec.yaml` 读取版本号。
2. 如果 `v<version>` 标签不存在且版本高于当前最新标签，继续发布。
3. 构建正式签名 Android APK。
4. 构建 ad-hoc macOS DMG。
5. 汇总变更日志并创建 GitHub Release。

如果只是想触发新版本更新，提升 `flutter_app/pubspec.yaml` 的 `version` 即可。

---

## 🤖 面向 AI 协作的规则

根目录的 `架构指南.md` 是 AI 协作约束草案，实际代码以当前 README 和源码为准。给 AI 分配任务时建议明确目标层：

- “只改 Flutter UI”
- “扩展 CastPigeonApi 并接入 Android/macOS”
- “只改 Android runtime”
- “只改 macOS runtime”
- “只改 KMP core”

这样可以避免 UI、平台能力、KMP core 之间互相穿透。

---

<div align="center">
  <p><i>"超越屏幕边界，让数据像信鸽一样自由翱翔。"</i></p>
  <p><b>CastPigeon Team @ 2026</b></p>
</div>
