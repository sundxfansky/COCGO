# 插件发布、迁移与验证

新的发布链路为：编译独立 DEX JAR → 兼容旧格式的 ChaCha20 包装 → RSA/SHA-256 签名 → 管理员上传 → 客户端验签 → 停止旧脚本 → 加载新版。签名覆盖 `文件名 UTF-8 + NUL + 完整密文`，因此不能通过改名伪造新版本。

## 一次性配置

在受控的私有目录生成发布密钥（不要放进仓库或上传到云端）：

```sh
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /private/location/plugin-signing.pem
chmod 600 /private/location/plugin-signing.pem
openssl pkey -in /private/location/plugin-signing.pem -pubout -outform DER | openssl base64 -A
```

将最后输出的**公钥**配置到两个位置：

- Android `local.properties` 中的 `HOT_UPDATE_PUBLIC_KEY=<base64 DER>`，或构建环境的同名变量。
- Docker 部署 `.env` 中的 `PLUGIN_PUBLIC_KEY=<同一公钥>`；Compose 会传给后端。

私钥仅由发布人员保管。仓库不会自动生成生产信任根，缺少公钥时远程更新会明确拒绝执行。旧的未签名下载接口已经不再使用；客户端及云端须一起升级。旧密文格式可解密，但发布前仍须用新工具签名，插件也须按当前 MainCode 接口重新编译。

## 构建与发布

```sh
./gradlew :app:buildJar
# 产物：app/build/plugin/code.jar，不再删除或修改 src/main/assets 中的旧产物。

python3 scripts/release_plugin.py \
  --input app/build/plugin/code.jar \
  --key /private/location/plugin-signing.pem
```

产物位于 `app/build/plugin/releases/`，包括 `encrypted_<version>.jar` 和二进制 `.jar.sig`。加密本身用于格式兼容，真实性来自独立 RSA 签名。

上传时先登录管理平台获取管理员会话 Token，放入 `SUN_UPLOAD_TOKEN` 环境变量，然后运行：

```sh
./gradlew uploadJar \
  -PsigningKey=/private/location/plugin-signing.pem \
  -PuploadUrl=https://your-host/api/v1
```

可用 `-Ppython=/path/to/python3` 指定 Python。上传命令不会把 Token 写进命令行参数。发布前保留原有 `validateUploadJarGuard` 检查：MainScript 的测试入口必须关闭。

客户端“云端配置”中的 API 地址和 Token 同时用于同步及下载插件。签名验证、版本检查、实际类加载失败时保留旧插件；设置窗口打开时延后热更新，保护未保存的草稿。

## 调试与增量构建

```sh
./gradlew assembleDebug -Pabis=arm64-v8a
./gradlew deployAndReload -Pdevice=<serial>
```

不传 `abis` 时构建四种 ABI。Rust 任务跟踪源码、Cargo 锁文件、ABI、Cargo 和 NDK 路径；不变时显示 UP-TO-DATE。旧 C++ 源码仍保留，但不再编译或装入 APK。

`deployAndReload` 使用 SDK 自带 adb、统一设备选择，检查每个子命令结果；只删除自己创建的临时文件。仅 Debug 接受未签名本地插件及调试重载广播。

## 内部 Root 后端迁移

文件、配置、Shell 与触摸已迁移到宿主内部 Root 工作进程，直接加载当前安装的宿主 APK，并通过私有标准输入输出管道通信。不再需要构建或复制 sunserver APK，也不监听 `6839` 端口；旧 HTTP/WebSocket 工具不能再连接宿主执行 Root 操作。

`ServerActions`、`FileHelper`、`ServerShell` 和 `RunShell` 保留现有脚本调用入口。`ServerActions` 保留原包名与宿主类型，确保动态 JAR 的 `MainCode.refreshConfigs(ServerActions)` 接口兼容。外部配置文件继续使用原路径，保存成功后才发布新的内存配置。

修改 Root 后端需更新宿主 APK。触摸目标选择还要求匹配的动态插件，更新时应同时构建宿主与插件。Android 悬浮窗 Service 和可选云同步不受此迁移影响。

## 会话与配置兼容性

- 新 Token 为数据库记录的随机会话，数据库只存摘要，有效期 30 天；`POST /api/v1/auth/logout` 立即撤销。
- 旧 HMAC Token 只有从数据库首次迁移起 7 天的过渡期，重启不会延长，注销也可立即撤销。用户应重新登录，并更新 Android 保存的 Token。
- 邮箱验证码最多尝试 5 次，预算存入数据库；重发才重置。登录、注册、验证还有账号及 TCP 来源速率限制。未信任任意转发头，反向代理后的来源配额会共享。
- `PUT /api/v1/config` 必须携带 `If-Match: <读取时的版本>`。缺失返回 428；版本冲突返回 409，数据保持原样。网页展示远端内容用于合并，并保留草稿。
- Android 在设置打开期间将新云配置放入待处理区，用户选择应用远端或保留本地；已处理的云版本保存在私有偏好中，重启不会重复覆盖同一版本。

## 可复现验证

```sh
./gradlew :app:testDebugUnitTest :app:buildJar :app:assembleDebug :app:assembleRelease
go -C backend test -race ./...
go -C backend vet ./...
python3 -m unittest discover -s scripts -p 'test_*.py'
(cd web && npm ci && npm run build)
python3 scripts/smoke_root.py --device <serial>
python3 scripts/smoke_cloud.py
```

设备 smoke 使用独立临时目录和 app_process 进程，不安装、停止或启动用户应用。它验证两版插件与宿主同名类的隔离、宿主接口身份及真实插件入口，结束时只清理自己的文件。内部 Root 文件、Shell、配置与触摸通过宿主 Android 设备测试验证，测试包须使用实际宿主的 Root 授权；运行前应停止辅助。
