# Local Debug Workflow

This is the shortest supported loop for running COCGO on a rooted Android emulator.

## One-time setup

Install Android Studio, SDK Platform 36, Build Tools, NDK 28.2.13676358, Rust, and `cargo-ndk`.

```sh
rustup default stable
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
cargo install cargo-ndk
```

Start an emulator and confirm that it is visible:

```sh
adb devices
```

The emulator/device must provide Root (`adb shell su -c id`) and allow overlay, notification,
accessibility, and screen-capture permissions.

## Build and run

From the repository root:

```sh
./gradlew assembleDebug
./gradlew -Pdevice=emulator-5554 runDebugOnDevice
```

`assembleDebug` automatically builds Rust for all four ABIs and packages the bundled
`app/src/main/assets/server.apk`. The second command installs the APK, stops any previous
application process, and launches `MainActivity`.

For a single connected device, `-Pdevice=...` can be omitted. The generated APK is:
`app/build/outputs/apk/debug/app-debug.apk`.

## Dependency services

The Root operation server is maintained in `third_party/zkqserver` and is already bundled as
`server.apk`. To rebuild that asset after changing its source:

```sh
cd third_party/zkqserver
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ../../app/src/main/assets/server.apk
cd ../..
```

`third_party/building_plugin` is optional. Build and install it separately when OCR/building
detection is needed:

```sh
cd third_party/building_plugin
./gradlew assembleDebug
cd ../..
```

## Logs and restart

```sh
./gradlew -Pdevice=emulator-5554 debugLogs
./gradlew -Pdevice=emulator-5554 stopDebugServer
adb -s emulator-5554 shell am force-stop com.coc.zkqcode
```

The app starts one `ShellServer` instance on `localhost:6839` and reuses it when it is healthy.
If startup is stuck at “等待Root服务器启动”, stop the server once, launch the app again, and
inspect `debugLogs` for permission or Root errors.

## Hot reload

When a timestamped JAR is present in `app/src/main/assets`, the loader prefers the newest one.
The existing `deployAndReload` task can push it to a rooted device and send the reload broadcast.
For the normal debug build no JAR is required: the plugin classes are loaded directly from the APK.
