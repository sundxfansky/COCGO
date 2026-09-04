# 本地启动与调试指南

本文覆盖从空白 macOS/Linux 主机到 Root Android 模拟器的完整流程。项目是 Kotlin/Jetpack Compose 主界面加 Rust 原生库的 Android 自动化宿主；启动后还会安装并拉起内置的 `ZKQserver`，通过 `ws://localhost:6839/zkq` 提供文件和操作服务。

## 1. 一键准备环境

在仓库根目录执行：

```sh
chmod +x scripts/setup_dev.sh
./scripts/setup_dev.sh
```

脚本会检测或安装 JDK、Android command-line tools、SDK Platform 36、Build Tools 36、NDK `28.2.13676358`、CMake `3.22.1`、Rust 四个 Android target 和 `cargo-ndk`，然后构建并打包 `ZKQserver` 与主应用。它只写仓库内的 `local.properties`，不会修改 shell 配置文件。

常用选项：

```sh
./scripts/setup_dev.sh --check-only
./scripts/setup_dev.sh --skip-build
./scripts/setup_dev.sh --build-building-plugin
./scripts/setup_dev.sh --run --device emulator-5554
```

Linux 主机需要自行安装 Android Studio 或 command-line tools；macOS 使用 Homebrew 时脚本可以代办部分工具。若脚本提示 `ANDROID_SDK_ROOT`，将其设置为 SDK 根目录后重试。

## 2. 手动环境要求

- JDK 17 或更高版本（Gradle 使用 Java 17）。
- Android SDK Platform 36、Build Tools 36.0.0、NDK 28.2.13676358、CMake 3.22.1。
- Rust stable、`cargo-ndk`，以及四个 Android targets：`aarch64-linux-android`、`armv7-linux-androideabi`、`i686-linux-android`、`x86_64-linux-android`。
- `adb` 和可获取 Root 的 Android 模拟器/设备。推荐 Android Studio AVD；普通未 Root 设备无法完成服务启动。

验证工具链：

```sh
java -version
./gradlew --version
rustc --version && cargo-ndk --version
adb version
echo "$ANDROID_SDK_ROOT"
test -d "$ANDROID_SDK_ROOT/ndk/28.2.13676358"
rustup target list --installed
```

## 3. 创建并验证 Root 模拟器

启动 AVD 后确认序列号：

```sh
adb devices
adb -s emulator-5554 shell getprop ro.build.version.sdk
adb -s emulator-5554 shell su -c id
```

最后一条应包含 `uid=0(root)`。在 Android 设置中为应用授予 Root、悬浮窗、通知、无障碍和录屏权限；首次启动会逐项请求。没有录屏权限时图色识别和自动点击不会工作。

## 4. 构建依赖与主应用

`third_party/zkqserver` 是必须的 Root WebSocket 服务，构建后复制为主应用资产：

```sh
cd third_party/zkqserver && ./gradlew assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk ../../app/src/main/assets/server.apk
cd ../..
./gradlew assembleDebug --no-daemon
```

主应用的 `assembleDebug` 会先执行 `rustBuild`，为四种 ABI 生成 `librust_logic.so`。`third_party/building_plugin` 是可选建筑检测/OCR 服务：

```sh
cd third_party/building_plugin && ./gradlew assembleDebug --no-daemon
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

没有插件时主应用仍能运行，插件相关功能会不可用。

## 5. 安装、启动和停止

```sh
./gradlew -Pdevice=emulator-5554 runDebugOnDevice
```

该任务会安装 `app-debug.apk`、停止旧进程并启动 `MainActivity`。只有一台设备时可以省略 `-Pdevice`。停止应用和内置服务：

```sh
./gradlew -Pdevice=emulator-5554 stopDebugServer
adb -s emulator-5554 shell am force-stop com.coc.zkqcode
```

## 6. 日志、端口与分层调试

持续查看过滤后的应用日志：

```sh
./gradlew -Pdevice=emulator-5554 debugLogs
adb -s emulator-5554 logcat -v time | rg 'ZKQ|Rust|ShellServer|coc.zkqcode'
```

Root 服务应监听 `6839`，可在模拟器内检查：

```sh
adb -s emulator-5554 shell su -c 'ss -lntp | grep 6839'
```

插件 HTTP 服务使用 `13462`：

```sh
adb -s emulator-5554 shell 'curl -sS http://127.0.0.1:13462/ || true'
```

Kotlin/Compose 调试使用 Android Studio 的 Debug；Rust/JNI 先看 `rust_logic` 的 Android log，再检查 ABI 与 NDK。修改动态模块 `app/src/main/java/com/coc/zkqcode/jar` 后，可使用已有 `deployAndReload` 任务热加载；它要求设备 Root，且资源、Manifest、JNI 变更仍需完整重装。

## 7. 常见故障

**没有设备**：启动 AVD，等待 `adb devices` 显示 `device`，不要使用 `unauthorized` 状态。

**找不到 cargo/cargo-ndk**：执行 `source "$HOME/.cargo/env"`，再运行 `cargo install cargo-ndk`。

**Rust target 缺失**：执行 `rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android`。

**NDK 或 `librust_logic.so` 缺失**：确认 `local.properties` 的 `sdk.dir` 正确且 NDK 目录为 `28.2.13676358`，然后运行 `./gradlew clean assembleDebug`。

**writable dex 错误**：Android 14+ 不允许覆盖只读资源；停止旧 Root server 后重新启动，当前 `ServerManager` 会自动复用健康实例并将 APK 设为只读。

**6839/13462 已占用**：停止旧应用/插件，或检查 `ss -lntp` 找出进程；不要同时启动多个 server APK。

**一直等待 Root 服务器**：确认 `adb shell su -c id` 成功，查看 `debugLogs` 中的安装、Root shell 和端口错误，再执行 `stopDebugServer` 后重试。

**权限未授予**：在系统设置中开启悬浮窗、无障碍、通知和录屏；Root 管理器中允许本应用和 `server.apk` 获取 Root。

## 8. 清理与产物

```sh
./gradlew clean
rm -rf rust_logic/target
./gradlew assembleDebug --no-daemon
```

主要产物：主 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，内置服务 APK 位于 `app/src/main/assets/server.apk`，Rust 中间产物位于 `rust_logic/target/`。调试时请保留首次失败的完整 Gradle 和 logcat 输出，便于定位具体层级。
