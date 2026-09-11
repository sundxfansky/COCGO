# 太阳

太阳是一款运行在 Root 权限 Android 环境下的部落冲突（Clash of Clans）自动化辅助软件，旨在为行动不便者、视觉障碍者等人群提供无障碍游玩能力。软件通过图色识别与模拟点击复现新手玩家的基本操作流程，不涉及内存读写、协议篡改或数据注入等作弊行为，也不会影响游戏内的对战公平性。

若你认为太阳存在侵权问题，请携带版权证据与太阳联系，太阳将在第一时间处理。本软件仅限自用，禁止用于商业行为，禁止用于违反游戏服务条款的行为。

## 目录

- [整体架构](#整体架构)
- [项目结构](#项目结构)
- [核心机制](#核心机制)
- [技术栈](#技术栈)
- [本地开发](#本地开发)
- [热重载小技巧](#热重载小技巧)
- [致谢](#致谢)
- [免责声明](#免责声明)

## 整体架构

太阳的主 APK 本身只是一个很薄的宿主（host loader）：界面框架、权限申请、Root 环境准备、原生库加载等基础设施都在主 APK 里，而实际的游戏自动化逻辑（判断当前界面、决定点哪里、执行升级/训练/攻打等策略）被拆分成独立的源码子树，在构建时打包为一个 JAR，由宿主在运行时通过 `DexClassLoader` 动态加载。这样设计的好处是：自动化脚本逻辑可以独立于宿主 APK 更新（热更新），不需要用户重新安装整个应用。

一次典型的启动流程大致是：

1. 主应用启动后检测 Root 权限（`CheckRoot.kt`），并申请悬浮窗、无障碍、通知、录屏等权限。
2. Root 校验通过后，`ServerManager` 会把内置在 `assets/server.apk` 中的 Root 服务解压到私有目录，通过 `app_process` 以 `su` 权限拉起一个不依赖标准 Android `Context` 的常驻进程（`third_party/sunserver`），监听本机 `6839` 端口，对外提供触摸模拟、文件读写等能力。
3. `Loadjar` 从 `assets` 目录中找到最新的插件 JAR（或者在开发调试时直接使用打包进主 APK 的插件类），通过 `DexClassLoader` 动态加载后展示脚本界面。
4. 脚本运行过程中，对屏幕不在私有目录内的所有文件读写都必须经由 `6839` 端口的 WebSocket/HTTP 服务完成，而不是直接调用文件系统 API——这是因为脚本进程本身运行在受限的沙箱里，只有 Root 服务进程才有权限跨包读写游戏数据目录。
5. 图色识别、多点触控模拟等对性能敏感的部分由 `rust_logic` 提供的 Rust 原生库通过 JNI 完成，Kotlin 侧只负责调用。
6. `HotUpdateManager` 会定期检查服务器上是否有新版本的加密 JAR，下载校验后通过 `Loadjar` 重新加载，无需重启 APP 或重新安装。
7. 若配置了云端配置服务（见下文 `backend/`、`web/`），`CloudConfigSync` 会在应用可见时定期轮询远端配置，实现多设备配置同步；未配置时应用完全离线运行，不受影响。

本分支已移除应用完整性校验、广告、用户登录/验证以及 Rust 反调试/反 Hook 逻辑，方便学习和自行构建。

## 项目结构

```
.
├── app/                    主应用（Kotlin + Jetpack Compose）
│   ├── src/main/java/com/coc/suncode/
│   │   ├── core/           宿主基础设施：权限、Root、悬浮窗、无障碍服务、
│   │   │                   截图/录屏、热更新、云配置同步、数据库
│   │   ├── loadjar/        DexClassLoader 动态加载插件 JAR 的逻辑
│   │   ├── nativehelper/   JNI 桥接层
│   │   └── jar/            【动态模块】游戏自动化逻辑与界面，打包为 JAR 后
│   │                       由宿主动态加载。构建/开发时视为独立单元。
│   │       ├── code/       按主世界(mainbase)/夜世界(builderbase)/账号与
│   │       │               颜色方案(auth, colorschema)/通用工具(universal)
│   │       │               划分的自动化脚本逻辑
│   │       └── ui/         配置项 Schema 与对应的 Compose 界面
│   └── src/main/cpp/       JNI 原生层（C++），转发调用到 rust_logic
├── rust_logic/             Rust 原生库源码，通过 cargo-ndk 编译为四种 ABI 的
│                           .so，供图色识别、加解密等性能敏感逻辑使用
├── third_party/
│   ├── sunserver/          Root 环境下常驻的 WebSocket/HTTP 服务，独立 Android
│   │                       工程，构建产物内置为 app/src/main/assets/server.apk
│   └── building_plugin/    可选的本地建筑检测 + OCR 推理服务，独立 Android 工程，
│                           不是主应用启动的必需项
├── backend/                可选的云端配置同步服务：Go + SQLite
├── web/                    云端配置同步的管理网站：React + Vite
├── scripts/setup_dev.sh    一键检测/安装本地开发环境并构建依赖服务
└── docs/                   本地调试、云端部署等详细文档
```

## 核心机制

### 宿主与动态 JAR

`app/src/main/java/com/coc/suncode/jar` 下的所有代码在构建时被视为一个独立单元，通过自定义 Gradle 任务（`buildJar`）用 `d8` 打包为 DEX 格式的 JAR，加密后放入 `assets`。宿主启动时优先加载 `assets` 中时间戳最新的 JAR；若找不到任何外部 JAR（例如首次调试构建），则直接使用打包进主 APK 的插件类作为兜底（`loadBundledPlugin`）。这套机制让脚本逻辑可以独立发布更新，而不必重新分发整个 APK。

### JNI 与 Rust 原生库

`app/src/main/cpp/native-lib.cpp` 通过 JNI 暴露 `NativeTools`（`com/coc/suncode/nativehelper/NativeTools`）给 Kotlin 层调用，实际计算逻辑委托给 `rust_logic`（多点找色、加解密、动态 DEX 处理等）。原生库按 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四种 ABI 编译，由 Gradle 的 `rustBuild` 任务在 `mergeDebugJniLibFolders`/`mergeReleaseJniLibFolders` 之前自动触发。

### Root 常驻服务（sunserver）

`third_party/sunserver` 是一个不依赖标准 Android `Context` 的进程，通过 `app_process` 以 `su` 权限启动，内嵌 WebSocket/HTTP 服务器监听 `6839` 端口，对外提供触摸模拟（`touch_action`）与文件系统操作（`file_action`）等能力，同时可以作为 WebSocket 客户端反向连接控制端（`16839` 端口）。主应用与该服务之间的所有跨包 I/O（读写游戏本身的数据目录等）都必须经过这层，因为脚本 JAR 运行在受限的应用沙箱内，没有直接访问其他应用私有目录的权限。完整协议见 [`third_party/sunserver/README.md`](third_party/sunserver/README.md)。

### 热更新

`HotUpdateManager` 会周期性向服务器查询最新加密 JAR 的版本号和 MD5，通过 PoW（工作量证明）挑战完成鉴权后下载、校验、原子替换本地文件，再调用 `Loadjar` 重新加载，全程无需重启应用或重新安装 APK。同时有一个看门狗协程：若开启了对应设置且 8 小时内没有收到脚本侧的更新信号，会强制触发一次检查，避免脚本卡死导致长期停留在旧版本。

### 云端配置同步（可选）

`backend/`（Go + SQLite）与 `web/`（React + Vite）组成一套独立、可选的云端配置服务：在网页上登录后编辑 JSON 配置并发布，Android 客户端的 `CloudConfigSync` 会在应用处于前台时基于 `ETag`/`If-None-Match` 定期轮询，只有版本号变化时才拉取并覆盖本地配置，从而实现多设备配置同步。未配置 `BASE_URL` 或未登录时，客户端完全离线运行，不受影响。部署步骤和完整 API 说明见 [`docs/CLOUD_DEPLOYMENT.md`](docs/CLOUD_DEPLOYMENT.md)。

### 可选的本地推理插件

`third_party/building_plugin` 是一个独立的本地 HTTP 推理服务，基于 ONNX 模型提供建筑检测与文字识别（OCR）能力，供脚本中的建筑升级判断等场景调用；未安装该插件时对应功能不可用，但不影响主应用的其他部分。

## 技术栈

| 层 | 技术 |
|----|------|
| 主应用 UI | Kotlin, Jetpack Compose |
| 原生层 | C++ (JNI), Rust (cargo-ndk 交叉编译) |
| Root 服务 | Kotlin, Ktor (embeddedServer + WebSocket 客户端) |
| 云端后端 | Go, SQLite (modernc.org/sqlite), bcrypt, HMAC token |
| 云端前端 | React, Vite |
| 构建系统 | Gradle (Kotlin DSL) + 自定义任务, Cargo |

## 本地开发

如果你想自己本地构建，可以参考下面的步骤。因为这个项目非常小，只有作者一个人在开发，所以一般会先在本地开发一段时间，然后再把代码上传。作者会尽量上传最新的代码，但不保证一定能确保代码是最新的。不过大家还是能从这个仓库里学到不少东西，比如怎么做不重启的热更新、怎么在安卓上跑 Rust 代码、怎么实现自动化操作之类的。

本仓库已将以下依赖源码整合到 `third_party/`，无需再单独下载：

- `third_party/sunserver`：Root 环境下的 WebSocket/HTTP 操作服务。构建主应用前可运行其 `./gradlew assembleDebug`，再将生成的 APK 放入 `app/src/main/assets/server.apk`。
- `third_party/building_plugin`：可选的本地建筑检测与 OCR 服务，包含 ONNX 模型和独立 Android 应用源码。它不属于主应用启动必需项。

1. **准备环境**
   推荐直接运行一键脚本：`./scripts/setup_dev.sh`。脚本会检查并安装 Android SDK/NDK、Rust 和 `cargo-ndk`，同时构建依赖服务。完整参数和手动安装方式见 [`docs/LOCAL_DEBUG.md`](docs/LOCAL_DEBUG.md)。
2. **构建依赖服务**
   `third_party/sunserver` 和 `third_party/building_plugin` 都是独立 Android 工程，可在各自目录运行 `./gradlew assembleDebug`。主应用已附带可直接启动的 `server.apk`。
3. **构建主应用**
   运行 `./gradlew assembleDebug`。Gradle 会先调用 `rustBuild` 构建四种 ABI 的 Rust 原生库，再生成 APK。
4. **运行**
   将 `app/build/outputs/apk/debug/app-debug.apk` 安装到已获取 Root 权限的模拟器或设备。项目自带 `server.apk`，首次启动时会自动运行本地服务。

完整的环境检查、模拟器启动、日志过滤和热加载流程见 [`docs/LOCAL_DEBUG.md`](docs/LOCAL_DEBUG.md)。常用快捷命令：

```sh
./gradlew -Pdevice=emulator-5554 runDebugOnDevice
./gradlew -Pdevice=emulator-5554 debugLogs
```

首次配置也可以分步执行：

```sh
./scripts/setup_dev.sh --check-only
./scripts/setup_dev.sh --run --device emulator-5554
```

## 热重载小技巧

本项目支持像按键精灵/懒人精灵那样一键运行脚本，不用启动界面。步骤如下：

1. 把 `app/src/main/java/com/coc/suncode/jar` 文件夹备份到别的地方，然后从项目里删掉。
2. 构建项目，装到模拟器上。
3. 把第一步备份的 jar 文件夹放回原来的位置。
4. 运行 `deployAndReload`，就能一键跑脚本了。

## 致谢

本项目的开发离不开以下开源项目和库的支持，在此表示感谢。

### 参考项目

| 项目 | 作者 | 说明 | 许可证 |
|------|------|------|--------|
| [scrcpy](https://github.com/Genymobile/scrcpy) | Genymobile | 屏幕镜像与控制，本项目参考了其设计思路 | Apache 2.0 |
| [libsu](https://github.com/topjohnwu/libsu) | topjohnwu | Android Root Shell 库 | Apache 2.0 |
| [Reorderable](https://github.com/Calvin-LL/Reorderable) | Calvin Liang | Compose 拖拽排序组件，代码直接引用于本项目 | Apache 2.0 |

### 使用的开源库

#### Android / Kotlin

| 库 | 作者 | 说明 |
|----|------|------|
| [OkHttp](https://github.com/square/okhttp) | Square | HTTP 客户端 |
| [Gson](https://github.com/google/gson) | Google | JSON 序列化/反序列化 |
| [Ktor](https://github.com/ktorio/ktor) | JetBrains | 异步服务器框架（WebSocket 等） |
| [Timber](https://github.com/JakeWharton/timber) | Jake Wharton | 日志工具 |
| [ML Kit Text Recognition](https://developers.google.com/ml-kit) | Google | OCR 文字识别 |

#### Rust

| 库 | 说明 |
|----|------|
| [jni](https://crates.io/crates/jni) | Rust 与 Java/Kotlin 的 JNI 交互 |
| [android_logger](https://crates.io/crates/android_logger) | Android 平台日志输出 |
| [libc](https://crates.io/crates/libc) | Android 底层系统调用绑定 |

## 免责声明

本项目已在 Gitee 平台开源，所有源代码公开透明，接受社区审查。本软件仅通过图色识别与模拟点击的方式，复现新手玩家的基本操作流程，不涉及任何内存读写、协议篡改或数据注入等违规/作弊行为。所有权限（即 Root 权限）均由用户手动授予，合法合规。本质原理和用户对游戏进行录屏，或者开鼠标宏操作一样，完全绿色安全。如果本软件违规，那么所有游戏录屏软件以及鼠标宏/安卓模拟器均违规。本软件无侵入性功能，不会对游戏内的对战环境产生任何影响，不影响游戏公平性。

严禁将本项目用于任何违反法律法规或游戏服务条款的用途。若因使用者自身行为导致任何法律纠纷或账号处罚，一切后果由使用者本人承担，与本项目开发者无关。
