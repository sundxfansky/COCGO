# 本地启动与调试指南

项目使用 Kotlin/Jetpack Compose、Rust 原生库和宿主内置的 Root 后端。文件、配置、Shell 与触摸通过宿主加载的 Root 工作进程执行，不需要独立的服务 APK，也不监听文件服务端口。

## 1. 准备工具链

在仓库根目录执行：

```sh
./scripts/setup_dev.sh
```

脚本检测或安装 JDK、Android command-line tools、SDK Platform 36、Build Tools 36、NDK `28.2.13676358`、CMake `3.22.1`、Rust 四个 Android target 和 `cargo-ndk`，然后构建主应用。它只在仓库内写入 `local.properties`，不修改 shell 配置文件。

```sh
./scripts/setup_dev.sh --check-only
./scripts/setup_dev.sh --skip-build
./scripts/setup_dev.sh --run --device 127.0.0.1:5555
```

依赖 JDK 17+、Android SDK/NDK、Rust stable、`cargo-ndk`、ADB 和已获取 Root 的 Android 设备或模拟器。Linux 主机需自行安装 Android Studio 或 command-line tools。SDK 无法自动定位时设置 `ANDROID_SDK_ROOT`。

```sh
java -version
./gradlew --version
rustc --version
cargo-ndk --version
adb version
rustup target list --installed
```

四个 Rust target 为 `aarch64-linux-android`、`armv7-linux-androideabi`、`i686-linux-android`、`x86_64-linux-android`。

## 2. 连接设备并授权

```sh
adb connect 127.0.0.1:5555
adb devices
adb -s 127.0.0.1:5555 shell getprop ro.build.version.sdk
```

在 Root 管理器中授权 `com.coc.suncode`，并按应用提示授予悬浮窗、通知、无障碍和录屏权限。ADB shell 的 Root 授权与应用授权彼此独立：`adb shell su -c id` 被拒绝，不能据此判断应用是否已获 Root。内部 Root 回归应以实际宿主包名运行。

## 3. 构建并启动

```sh
./gradlew :app:assembleDebug :app:buildJar --no-daemon
./gradlew -Pdevice=127.0.0.1:5555 runDebugOnDevice
```

`assembleDebug` 自动执行 `rustBuild`，默认编译四种 ABI。可用 `-Pabis=arm64-v8a` 等参数缩小本次构建范围。内部 Root 工作进程直接使用当前安装的宿主 APK，修改其代码后必须更新宿主 APK。

`third_party/sunserver` 仅保留历史源码，不参与主应用构建。YOLO/OCR 使用主应用内的本地模型；`--build-building-plugin` 仅供需要单独构建历史识别插件时使用。

`runDebugOnDevice` 安装调试 APK、停止旧应用并启动 `MainActivity`。单设备时可省略 `-Pdevice`。正常退出使用应用退出按钮；需要强制停止时执行：

```sh
adb -s 127.0.0.1:5555 shell am force-stop com.coc.suncode
```

Root 后端通过私有管道绑定宿主生命周期，退出和管道关闭时释放资源。

## 4. 日志与验证

```sh
./gradlew -Pdevice=127.0.0.1:5555 debugLogs
adb -s 127.0.0.1:5555 logcat -v time | rg 'sun_debug|sun_rust|Root|coc.suncode'
./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

运行应用内日志按钮可打开半透明日志浮层，不会暂停辅助；日志默认触摸穿透，自动手势期间也会为目标应用让出触摸。截图时短暂隐藏日志以免干扰识别，可能产生短暂闪动。

Root 文件/Shell 的设备回归与触摸回归须使用实际宿主的 Root 授权。完整设备用例可通过 Android Studio 或 `connectedDebugAndroidTest` 执行。设备测试会启动测试界面，应在停止辅助后运行。

插件类加载隔离可单独验证；它使用临时目录，不安装、停止或启动用户应用：

```sh
python3 scripts/smoke_root.py --device 127.0.0.1:5555
```

Root 文件服务不再使用 `6839` 端口。历史插件的 `13462` HTTP 端口不是主应用本地识别和输入所需依赖。

## 5. 动态插件与产物

仅修改 `app/src/main/java/com/coc/suncode/jar` 时，可执行 `deployAndReload` 增量加载；Manifest、宿主类、资源和 JNI 变更仍需重装 APK。此任务要求 ADB shell 本身具有 Root 授权；只有应用获授权时，应通过应用内插件安装流程更新。

```sh
./gradlew deployAndReload -Pdevice=127.0.0.1:5555
```

主 APK：`app/build/outputs/apk/debug/app-debug.apk`。
动态插件：`app/build/plugin/code.jar`。
Rust 中间产物：`rust_logic/target/`。

发布流程见 [PLUGIN_RELEASE.md](PLUGIN_RELEASE.md)，内部兼容接口见 [ServerDoc.md](ServerDoc.md)，触摸限制见 [TargetInput.md](TargetInput.md)。

## 6. 常见故障

**没有设备**：等待 `adb devices` 显示 `device`；若为 `unauthorized`，先完成设备端授权。

**找不到 cargo/cargo-ndk**：执行 `source "$HOME/.cargo/env"`，再执行 `cargo install cargo-ndk`。

**NDK 或原生库缺失**：确认 `local.properties` 的 `sdk.dir` 正确、NDK 版本匹配，再构建主应用。

**Root 后端启动失败**：确认 Root 管理器允许实际宿主应用，更新 APK 后完全退出再启动，并检查应用内日志。无需检查端口或单独安装服务 APK。

**触摸被拒绝**：检查目标应用是否在前台、选择的版本和包名是否正确。触摸使用默认显示坐标，不支持后台游戏或独立虚拟显示。

**配置保存失败**：保留当前配置和错误日志，检查外部路径可访问性及剩余空间；不要删除已有配置文件来掩盖错误。
