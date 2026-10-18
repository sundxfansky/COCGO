# 太阳

Root 环境下运行的部落冲突（Clash of Clans）自动化客户端：Kotlin/Compose 宿主 + 动态加载的脚本模块 + Rust/JNI 原生计算 + 宿主内置的 Root 后端。

## 功能

- 基于屏幕取色和模板匹配判断游戏当前状态，模拟触摸完成升级建筑、训练部队、领取奖励等重复操作
- 脚本逻辑以 JAR 形式独立于主程序热更新，无需重新安装 APK
- 支持多账号切换、存档提取/写入
- 可选的 AI 辅助识别（YOLO 检测、OCR）
- 可选的多设备云端配置同步

## 架构总览

代码分为三层：

1. **宿主（`app/`）** — 常规安装的 Android APK，负责权限申请、Root 环境准备、动态加载脚本模块、以及生命周期管理。
2. **脚本模块（`app/src/main/java/com/coc/suncode/jar`）** — 实际的自动化逻辑和界面。构建为公共核心 `code.jar` 与登录、进攻、升级等独立功能 JAR，运行时由宿主通过 `DexClassLoader` 加载。功能包支持单独更新和启停，详见 [`docs/FEATURE_PLUGINS.md`](docs/FEATURE_PLUGINS.md)。
3. **宿主 Root 后端** — 直接加载已安装的宿主 APK，通过 `su app_process` 执行触摸、跨应用文件访问和 Shell 命令。宿主使用私有标准输入输出管道通信，不监听网络端口，不需要独立的服务 APK。

性能敏感的计算（取色、加解密等）通过 JNI 交给 `rust_logic` 里的 Rust 代码处理。

## 目录结构

| 路径 | 内容 |
|---|---|
| `app/` | 主应用源码（Kotlin, Jetpack Compose） |
| `app/src/main/java/com/coc/suncode/jar` | 动态加载的自动化脚本与界面 |
| `app/src/main/cpp` | JNI 层（C++），桥接到 Rust |
| `rust_logic/` | Rust 原生库，通过 `cargo-ndk` 编译 |
| `third_party/sunserver/` | 历史服务源码参考，不参与主应用构建和运行 |
| `third_party/building_plugin/` | 可选的建筑检测 + OCR 服务，独立 Android 工程 |
| `backend/` | 可选的云端配置同步后端（Go） |
| `web/` | 可选的云端配置同步管理页面（React） |
| `scripts/` | 本地开发环境搭建脚本 |
| `docs/` | 调试与部署文档 |

## 快速开始

依赖：JDK 17+、Android SDK/NDK、Rust（配合 `cargo-ndk`）、已获取 Root 权限的设备或模拟器。

```sh
# 一键检测/安装工具链并构建主应用
./scripts/setup_dev.sh

# 构建主应用（会先编译 Rust 原生库）
./gradlew assembleDebug

# 安装并启动
./gradlew -Pdevice=<设备序列号> runDebugOnDevice
```

Root 后端随主应用构建，不需要构建、复制或安装 `server.apk`。触摸和文件/Shell 使用各自的内部执行通道，日志浮层的 Android Service 继续由宿主管理。

详细的环境要求、模拟器配置和故障排查见 [`docs/LOCAL_DEBUG.md`](docs/LOCAL_DEBUG.md)。

## 常用 Gradle 任务

| 任务 | 作用 |
|---|---|
| `assembleDebug` | 构建调试 APK（自动触发 `rustBuild`） |
| `runDebugOnDevice` | 安装并启动应用 |
| `debugLogs` | 输出过滤后的应用与原生日志 |
| `buildJar` | 构建公共核心及全部功能包，核心包携带兼容的内置回退包 |
| `buildFeaturePlugins -Pfeature=main_attack` | 只打包所选功能到 app/build/plugin/features/ |
| `signFeaturePlugin -Pfeature=main_attack -PfeatureVersion=2 -PsigningKey=…` | 构建并签名单个功能包 |
| `deployAndReload` | 单独构建脚本模块 JAR 并推送到设备热重载，无需重装 APK |

## 技术栈

| 模块 | 技术 |
|---|---|
| 主应用 | Kotlin, Jetpack Compose |
| 原生计算 | Rust（cargo-ndk）, C++/JNI |
| Root 后端 | 宿主 Java/Kotlin、app_process、私有管道 |
| 云配置后端 | Go, SQLite |
| 云配置前端 | React, Vite |

## 云端配置同步（可选）

`backend/` 与 `web/` 提供一套可独立部署的账号配置同步服务，用于在多台设备间共享同一份配置。不部署不影响客户端本地运行。部署方式与接口说明见 [`docs/CLOUD_DEPLOYMENT.md`](docs/CLOUD_DEPLOYMENT.md)。

## 依赖库

| 库 | 用途 |
|---|---|
| [libsu](https://github.com/topjohnwu/libsu) | Root Shell 调用 |
| [OkHttp](https://github.com/square/okhttp) | 云端网络请求 |
| [Gson](https://github.com/google/gson) | JSON 序列化 |
| [ML Kit Text Recognition](https://developers.google.com/ml-kit) | 文字识别 |
| [Reorderable](https://github.com/Calvin-LL/Reorderable) | Compose 拖拽排序组件 |

## License

见 [LICENSE](LICENSE)。

签名热更新、会话迁移与内部 Root 后端说明见 [发布与迁移指南](docs/PLUGIN_RELEASE.md)。
