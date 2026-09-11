# 介绍

太阳旨在提供无障碍服务，帮助行动不便者、视觉障碍者等人群完成简易的游戏操作。

若您认为太阳存在侵权问题，请携带您的版权证据与太阳联系。太阳将在第一时间处理。

# 开箱即用

本软件为自用软件，禁止用于商业行为，禁止用于违反游戏规定的行为。

# 项目结构

| 目录 | 说明 |
|------|------|
| `app/` | 主应用，Kotlin + Jetpack Compose，动态加载 `app/src/main/java/com/coc/suncode/jar` 下的脚本逻辑，并通过 JNI 调用 `rust_logic` 原生库。 |
| `rust_logic/` | Rust 原生库源码，通过 `cargo-ndk` 编译为四种 ABI 的 `.so`，由主应用的 `rustBuild` Gradle 任务自动构建。 |
| `third_party/sunserver/` | Root 环境下的 WebSocket/HTTP 操作服务，独立 Android 工程，构建产物作为 `app/src/main/assets/server.apk` 内置于主应用。 |
| `third_party/building_plugin/` | 可选的本地建筑检测与 OCR 服务，独立 Android 工程，不属于主应用启动必需项。 |
| `backend/` | 可选的云端配置同步服务，Go + SQLite，供多设备同步账号配置使用。 |
| `web/` | 云端配置同步的管理网站，React + Vite。 |
| `scripts/setup_dev.sh` | 一键检测/安装本地开发环境并构建依赖服务。 |
| `docs/` | 本地调试与云端部署等详细文档。 |

# 开发

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

本分支已经移除应用完整性校验、广告、用户登录/验证以及 Rust 反调试/反 Hook 逻辑。未提供外部插件 JAR 时，应用会直接加载 APK 中内置的功能模块。

## 云端配置同步（可选）

`backend/` 和 `web/` 提供一套可选的云端配置同步服务：登录后在网页上编辑 JSON 配置并发布，多台设备会通过 `CloudConfigSync` 每 30 秒轮询同步到最新版本（基于 `ETag`/`If-None-Match`，未变化时返回 304）。本地不部署也不影响主应用离线使用。部署步骤和 API 说明见 [`docs/CLOUD_DEPLOYMENT.md`](docs/CLOUD_DEPLOYMENT.md)。

# 小技巧

本项目支持像按键精灵/懒人精灵那样一键运行脚本，不用启动界面。步骤如下：

1. 把 `app\src\main\java\com\coc\suncode\jar` 文件夹备份到别的地方，然后从项目里删掉。
2. 构建项目，装到模拟器上。
3. 把第一步备份的 jar 文件夹放回原来的位置。
4. 运行 `deployAndReload`，就能一键跑脚本了。

# 致谢

本项目的开发离不开以下开源项目和库的支持，在此表示感谢。

## 参考项目

| 项目 | 作者 | 说明 | 许可证 |
|------|------|------|--------|
| [scrcpy](https://github.com/Genymobile/scrcpy) | Genymobile | 屏幕镜像与控制，本项目参考了其设计思路 | Apache 2.0 |
| [libsu](https://github.com/topjohnwu/libsu) | topjohnwu | Android Root Shell 库 | Apache 2.0 |
| [Reorderable](https://github.com/Calvin-LL/Reorderable) | Calvin Liang | Compose 拖拽排序组件，代码直接引用于本项目 | Apache 2.0 |

## 使用的开源库

### Android / Kotlin

| 库 | 作者 | 说明 |
|----|------|------|
| [OkHttp](https://github.com/square/okhttp) | Square | HTTP 客户端 |
| [Gson](https://github.com/google/gson) | Google | JSON 序列化/反序列化 |
| [Ktor](https://github.com/ktorio/ktor) | JetBrains | 异步服务器框架（WebSocket 等） |
| [Timber](https://github.com/JakeWharton/timber) | Jake Wharton | 日志工具 |
| [ML Kit Text Recognition](https://developers.google.com/ml-kit) | Google | OCR 文字识别 |

### Rust

| 库 | 说明 |
|----|------|
| [jni](https://crates.io/crates/jni) | Rust 与 Java/Kotlin 的 JNI 交互 |
| [android_logger](https://crates.io/crates/android_logger) | Android 平台日志输出 |
| [libc](https://crates.io/crates/libc) | Android 底层系统调用绑定 |

# 免责声明

本项目已在 Gitee 平台开源，所有源代码公开透明，接受社区审查。本软件仅通过图色识别与模拟点击的方式，复现新手玩家的基本操作流程，不涉及任何内存读写、协议篡改或数据注入等违规/作弊行为。所有权限（即Root权限）均由用户手动授予，合法合规。本质原理和用户对游戏进行录屏，或者开鼠标宏操作一样，完全绿色安全。如果本软件违规，那么所有游戏录屏软件以及鼠标宏/安卓模拟器均违规。本软件无侵入性功能，不会对游戏内的对战环境产生任何影响，不影响游戏公平性。

严禁将本项目用于任何违反法律法规或游戏服务条款的用途。若因使用者自身行为导致任何法律纠纷或账号处罚，一切后果由使用者本人承担，与本项目开发者无关。
