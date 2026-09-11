# 太阳

太阳是一个跑在已获取 Root 权限的 Android 设备上的部落冲突（Clash of Clans）自动化工具。核心思路很简单：通过屏幕取色判断当前界面状态，再模拟触摸完成点击、滑动等操作，从而把日常重复性的操作（升级建筑、训练部队、领取奖励等）交给程序代劳。项目不读写游戏进程的内存，也不篡改网络协议，纯粹靠“看屏幕、模拟手指”的方式运作。

如果你是版权方，认为项目中有内容侵犯了你的权益，请携带证明材料联系作者，会第一时间处理。本项目仅供个人学习和自用，不得用于商业用途，使用者需自行确保遵守游戏的服务条款。

## 目录

- [这是什么](#这是什么)
- [目录结构](#目录结构)
- [关键设计点](#关键设计点)
- [用到的技术](#用到的技术)
- [本地搭建](#本地搭建)
- [脚本调试小技巧](#脚本调试小技巧)
- [鸣谢](#鸣谢)
- [免责声明](#免责声明)

## 这是什么

代码分为两层：一层是"壳"，也就是主 APK 里负责界面、权限申请、Root 环境搭建、常驻服务拉起这些和具体游戏逻辑无关的部分；另一层是"脑子"，也就是真正判断该点哪里、该做什么的自动化脚本代码，被单独放在 `app/src/main/java/com/coc/suncode/jar` 目录下。构建时脚本这部分会被打成一个独立的 JAR，运行时由壳通过 `DexClassLoader` 动态加载进来。这么拆分的原因是：脚本逻辑经常要跟着游戏版本调整，而壳几乎不用变——把两者分开之后，更新脚本不需要重新发一个 APK 给用户装，直接推一个新 JAR 让客户端下载替换就行，也就是项目里说的"热更新"。

启动之后大致会发生这些事：

- 检查设备是否有 Root（对应 `CheckRoot.kt`），并引导用户开启悬浮窗、无障碍、通知、录屏等权限。
- Root 检查通过后，把内置在 `assets/server.apk` 里的一个小程序解压出来，用 `su` 权限通过 `app_process` 直接跑起来（这个程序不需要标准的 Android `Context`），它会监听本机 `6839` 端口，负责真正的触摸模拟和跨应用文件操作——这部分代码在 `third_party/sunserver`。
- 主程序从 `assets` 里找最新的脚本 JAR 加载起来，找不到就用编译进主 APK 里的那份兜底。
- 脚本要读写游戏本身数据目录下的文件时，不能直接用文件系统 API（脚本进程本身是被沙箱限制的），必须通过上面说的 `6839` 端口那个服务转一下。
- 取色、多点触控这些跑得比较频繁、对性能敏感的操作放在 `rust_logic` 这个 Rust 库里，通过 JNI 调用。
- 后台有个 `HotUpdateManager`，会定期问服务器有没有新的脚本 JAR，有就下载校验后原地替换、重新加载，不用重装 APP。
- 如果配置了云端配置同步（见下面的 `backend/`、`web/`），`CloudConfigSync` 会在 APP 前台时轮询远端配置，方便多台设备共用同一份配置；不配置的话完全不影响离线使用。

当前这个分支去掉了签名校验、广告、账号登录/鉴权，以及 Rust 那边的反调试/反 Hook 代码，方便阅读和自己编译。

## 目录结构

```
.
├── app/                    主 APK（Kotlin + Jetpack Compose）
│   ├── src/main/java/com/coc/suncode/
│   │   ├── core/           壳的基础设施：权限申请、Root 检测、悬浮窗、
│   │   │                   无障碍服务、截图/录屏、热更新、云配置同步、数据库
│   │   ├── loadjar/        用 DexClassLoader 加载脚本 JAR 的代码
│   │   ├── nativehelper/   JNI 调用的桥接层
│   │   └── jar/            脚本代码所在目录，构建时单独打成 JAR 动态加载
│   │       ├── code/       按主世界(mainbase)/夜世界(builderbase)/账号相关
│   │       │               (auth)/配色(colorschema)/通用工具(universal) 分类
│   │       │               的具体自动化逻辑
│   │       └── ui/         配置项定义（Schema）和对应的 Compose 界面
│   └── src/main/cpp/       JNI 层（C++），把调用转给 rust_logic
├── rust_logic/             Rust 代码，用 cargo-ndk 编成四种 ABI 的 .so，
│                           承担取色、加解密等计算量较大的部分
├── third_party/
│   ├── sunserver/          常驻 Root 服务，独立的 Android 工程，编译产物
│   │                       会被复制进 app/src/main/assets/server.apk
│   └── building_plugin/    可选的建筑识别 + OCR 服务，独立 Android 工程，
│                           不装也不影响主程序跑起来
├── backend/                云端配置同步的后端，Go + SQLite，可选
├── web/                    云端配置同步的管理页面，React + Vite，可选
├── scripts/setup_dev.sh    本地环境一键检测/安装脚本
└── docs/                   本地调试、云端部署相关的详细文档
```

## 关键设计点

### 脚本和壳是分开构建、分开加载的

`app/src/main/java/com/coc/suncode/jar` 里的东西不参与主 APK 常规的类加载流程，而是被 Gradle 自定义任务 `buildJar` 单独用 `d8` 编译成 DEX、打包成 JAR、加密后塞进 `assets`。壳启动时会去 `assets` 目录找带时间戳的最新 JAR，没有的话就退回到编译进 APK 里的那一份（`loadBundledPlugin`）。这套机制的价值在于脚本更新和 APK 发布可以完全解耦。

### 性能敏感的部分丢给 Rust

`app/src/main/cpp/native-lib.cpp` 用 JNI 把一个叫 `NativeTools` 的类暴露给 Kotlin，背后实际干活的是 `rust_logic`（找色、加解密、动态 DEX 相关处理）。这个库会分别编出 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四份 `.so`，Gradle 里配了 `rustBuild` 任务在合并 JNI 库那一步之前自动跑。

### 为什么需要一个独立的常驻服务

`third_party/sunserver` 单独存在的原因是：脚本进程运行在应用沙箱里，没权限碰其他 APP 的数据目录，但很多自动化操作（比如读写游戏本身的存档相关文件）恰恰需要跨包权限。所以专门起了一个不挂靠任何 Activity、用 `su` 直接跑起来的进程，通过本地 WebSocket/HTTP（监听 `6839`）把触摸模拟和文件操作包装成接口暴露出来，同时它还能反过来作为客户端连接一个控制端（`16839`）。协议细节写在 [`third_party/sunserver/README.md`](third_party/sunserver/README.md)。

### 热更新怎么做到不用重装

`HotUpdateManager` 会周期性去问服务器最新脚本 JAR 的版本号和 MD5，用一次 PoW 挑战验证身份后下载、校验哈希、原子性替换本地文件，再交给 `Loadjar` 重新加载进程内的实例。另外还有一个看门狗：如果开了对应选项而且 8 小时没收到脚本那边发来的心跳信号，会强制触发一次检查，防止脚本卡死之后一直停在旧版本上。

### 云端配置同步是完全可选的附加件

`backend/`（Go + SQLite）配 `web/`（React + Vite）构成一套独立部署的配置管理服务：网页上登录、编辑一份 JSON 配置、点发布，客户端的 `CloudConfigSync` 在前台运行时按 `ETag`/`If-None-Match` 轮询，版本号变了才会把新配置拉下来覆盖本地。没配 `BASE_URL` 或者没登录，客户端该怎么跑还怎么跑，不受影响。部署方法和接口说明放在 [`docs/CLOUD_DEPLOYMENT.md`](docs/CLOUD_DEPLOYMENT.md)。

### 建筑识别插件

`third_party/building_plugin` 是另一个独立进程，本地起一个 HTTP 服务，用 ONNX 模型做建筑检测和文字识别，脚本里判断建筑升级状态之类的场景会调它。不装这个插件的话相关判断功能用不了，其余部分照常运行。

## 用到的技术

| 部分 | 技术 |
|----|------|
| 主 APK 界面 | Kotlin, Jetpack Compose |
| 原生层 | C++（JNI）、Rust（用 cargo-ndk 交叉编译） |
| 常驻 Root 服务 | Kotlin, Ktor（内嵌 HTTP 服务端 + WebSocket 客户端） |
| 云配置后端 | Go, SQLite（modernc.org/sqlite）, bcrypt, HMAC token |
| 云配置前端 | React, Vite |
| 构建工具 | Gradle（Kotlin DSL）+ 一堆自定义任务, Cargo |

## 本地搭建

这是个人业余维护的小项目，通常是攒够一段时间的改动之后再统一推上来，所以仓库里的代码不一定是作者本地最新的进度。如果你想自己编译跑一下，可以按下面的步骤来；顺带一提，这个仓库里能看到一些还算完整的实践，比如安卓上怎么做不重启的热更新、怎么把 Rust 塞进安卓项目里跑、以及一套简单的自动化点击是怎么搭起来的。

`third_party/` 下面已经带了两个依赖的完整源码，不用额外去别的地方下：

- `third_party/sunserver`：前面提到的常驻 Root 服务。构建主 APK 之前，可以先进这个目录跑 `./gradlew assembleDebug`，把产物覆盖到 `app/src/main/assets/server.apk`。
- `third_party/building_plugin`：可选的建筑识别/OCR 服务，带 ONNX 模型和完整 Android 工程。不参与主程序的必要构建流程。

1. **装环境**：直接跑 `./scripts/setup_dev.sh` 最省事，它会检查/安装 Android SDK、NDK、Rust 和 `cargo-ndk`，并顺手把依赖服务编译好。手动装的步骤和更多参数见 [`docs/LOCAL_DEBUG.md`](docs/LOCAL_DEBUG.md)。
2. **编依赖服务**：`third_party/sunserver` 和 `third_party/building_plugin` 都各自是独立的安卓工程，分别进去跑 `./gradlew assembleDebug` 即可；不想自己编的话主 APK 里已经带了能直接用的 `server.apk`。
3. **编主 APK**：仓库根目录跑 `./gradlew assembleDebug`，Gradle 会先跑 `rustBuild` 把四种 ABI 的 Rust 库编出来，再打主 APK。
4. **装到设备上**：把 `app/build/outputs/apk/debug/app-debug.apk` 装到一台已经有 Root 的模拟器或真机上就能跑，内置的 `server.apk` 会在首次启动时自动拉起。

模拟器怎么开、日志怎么过滤、脚本怎么热加载这些更细的流程写在 [`docs/LOCAL_DEBUG.md`](docs/LOCAL_DEBUG.md) 里，常用命令举例：

```sh
./gradlew -Pdevice=emulator-5554 runDebugOnDevice
./gradlew -Pdevice=emulator-5554 debugLogs
```

也可以分步跑：

```sh
./scripts/setup_dev.sh --check-only
./scripts/setup_dev.sh --run --device emulator-5554
```

## 脚本调试小技巧

想要那种类似按键精灵/鼠标宏工具的体验——开机直接跑脚本，不进任何界面——可以这么做：

1. 把 `app/src/main/java/com/coc/suncode/jar` 整个文件夹挪到别处备份一下，原地删掉。
2. 正常构建、装到模拟器/设备上。
3. 把第 1 步备份的 jar 文件夹放回原来的位置。
4. 跑一下 `deployAndReload` 这个 Gradle 任务，脚本就直接跑起来了，不用打开 APP 的界面。

## 鸣谢

以下开源项目和库对本项目帮助很大，在这里表示感谢。

### 参考过的项目

| 项目 | 作者 | 用在哪 | 许可证 |
|------|------|------|--------|
| [scrcpy](https://github.com/Genymobile/scrcpy) | Genymobile | 屏幕镜像/控制部分参考了它的设计 | Apache 2.0 |
| [libsu](https://github.com/topjohnwu/libsu) | topjohnwu | Android 上跑 Root Shell 命令 | Apache 2.0 |
| [Reorderable](https://github.com/Calvin-LL/Reorderable) | Calvin Liang | Compose 里的拖拽排序组件，代码直接拿来用了 | Apache 2.0 |

### 依赖的开源库

#### Android / Kotlin

| 库 | 作者 | 用途 |
|----|------|------|
| [OkHttp](https://github.com/square/okhttp) | Square | HTTP 请求 |
| [Gson](https://github.com/google/gson) | Google | JSON 序列化 |
| [Ktor](https://github.com/ktorio/ktor) | JetBrains | 异步服务端框架（内嵌 HTTP/WebSocket） |
| [Timber](https://github.com/JakeWharton/timber) | Jake Wharton | 日志 |
| [ML Kit Text Recognition](https://developers.google.com/ml-kit) | Google | 文字识别 |

#### Rust

| 库 | 用途 |
|----|------|
| [jni](https://crates.io/crates/jni) | 跟 Java/Kotlin 之间的 JNI 交互 |
| [android_logger](https://crates.io/crates/android_logger) | 把日志输出到 Android 的 logcat |
| [libc](https://crates.io/crates/libc) | 系统调用绑定 |

## 免责声明

代码在 Gitee 上完全公开，任何人都可以看、可以审查。这个工具做的事情仅限于"看屏幕取色 + 模拟点击"，不读写游戏进程内存，不改任何网络协议或数据包，跟人手动点屏幕在效果上没有区别。用到的 Root 权限也是使用者自己主动开的。原理上跟录屏软件或者鼠标宏工具类似，不会干扰游戏内的对战环境，也不构成对其他玩家的不公平。

请不要把这个项目用在任何违反法律法规或者游戏服务条款的地方。使用者自己的行为造成的任何法律责任或者账号处罚，都跟本项目的开发者没有关系。
