# 独立功能插件

首次迁移需要安装包含新插件接口与管理器的 APK。若设备已有旧版动态核心，还需通过原热更新流程加载本次 `buildJar` 生成的核心，或在调试设备执行 `deployAndReload`；宿主继续兼容旧核心，不会强制覆盖已安装的旧核心。之后修改某个功能实现，可只分发该功能的 JAR；无需重新安装 APK，也无需更新其他功能包。

## 当前拆分

| 插件 ID | 功能 |
|---|---|
| `login` | 自动登录准备、进入游戏、手动上号和运行时切号入口 |
| `main_attack` | 主世界练兵、搜敌、部署与战斗 |
| `builder_attack` | 夜世界练兵与战斗 |
| `clan` | 加入部落、请求援军、捐兵 |
| `main_research` | 主世界研究 |
| `builder_research` | 夜世界研究 |
| `upgrade` | 两个世界的建筑与城墙升级 |
| `obstacles` | 两个世界的障碍清理 |
| `collection` | 资源、奥仔小屋及成就奖励收集 |
| `heroes` | 英雄旗帜、装备和宠物操作入口 |
| `tutorial` | 主世界教程检查 |
| `account_create` | 批量创建账号 |

原来的功能配置继续生效：启用插件不等于开启该插件的所有功能。例如，启用进攻插件后，仍需启用对应账号的自动进攻设置。英雄升级入口保留原来的调用状态，本次没有额外开启该流程。

## 构建与签名

```sh
# 仅打包主世界进攻；featureVersion 必须为正整数，更新时递增。
./gradlew :app:buildFeaturePlugins -Pfeature=main_attack -PfeatureVersion=2

# 输出：app/build/plugin/features/main_attack.jar
# 也可直接调用 :app:buildFeatureMainAttack。

# 构建并签名，生成 main_attack.jar.sig；私钥不提交到仓库。
./gradlew :app:signFeaturePlugin -Pfeature=main_attack -PfeatureVersion=2 \
  -PsigningKey=/private/plugin-key.pem

# 构建所有功能包及带内置回退包的核心包。
./gradlew :app:buildJar
```

构建仍复用项目的 Kotlin 编译任务，但每个 D8 打包任务只包含目录清单指定的功能类。单功能构建只执行所选插件的打包任务。Python 3 用于依赖边界检查，OpenSSL 用于签名。

签名使用 RSA/SHA-256，覆盖完整 JAR 字节，包括内部 `feature.properties` 的 ID、版本和 API。宿主需要通过 `local.properties` 或环境变量配置 `HOT_UPDATE_PUBLIC_KEY`，值为对应 RSA 公钥的 X.509 DER Base64。此公钥设置与原核心包热更新共用，修改公钥需要重建 APK。

功能包的签名格式与旧核心包不同：功能包不使用旧加密封装和文件名签名，不要用 `release_plugin.py` 签功能包。核心包仍沿用原发布流程。

## 安装、启停与恢复

1. 将签名后的 JAR 与 `.jar.sig` 放在同一 HTTPS 路径，例如 `https://example.com/plugins/main_attack.jar` 和 `https://example.com/plugins/main_attack.jar.sig`。
2. 打开主页设置中的“功能插件”，选择对应功能，填入 JAR 地址，点击“安装所选插件”。下载使用直接 HTTPS 地址，不跟随重定向。
3. 安装会检查签名、ID、API、递增版本及入口接口。成功后辅助保持暂停，点击“保存并运行”继续。
4. 可单独启用、停用插件，或将所选插件恢复为当前核心版本的内置实现。插件开关为本机设置，立即保存，不参与账号配置或云端配置同步。

登录是自动化前置条件。停用登录插件时，自动流程会暂停，手动上号和工具栏切号也不能绕过此开关。其他停用插件会跳过其步骤。捐兵触发的进攻、夜世界进攻中的资源收集同样通过调度入口，遵守相应插件开关。

下载及安装失败不会改变已选择的版本。覆盖版本在加载阶段损坏或入口不兼容时会记录原因，并尝试当前核心携带的内置实现。执行过程中出现异常交给现有宿主异常暂停机制处理，不会自动重放可能已经发生的触摸；可在设置中恢复内置版本后再运行。

当前单功能更新通过管理页的 HTTPS 地址安装；原“自动更新”仍负责完整核心包。无需新增监听端口、文件服务器或外部服务 APK。

## 开发边界

- `interfaces/FeaturePlugin.kt` 是宿主 ABI，功能入口实现 `FeaturePlugin`。`FeatureResult` 区分成功、失败、跳过及异步授权。
- `jar/features/<id>/Entry.kt` 定义功能动作；实际业务代码继续位于 `jar/code` 中，避免不必要的文件移动。
- `app/src/main/assets/feature-plugins.json` 是唯一的类归属清单，同时供 Gradle 和宿主加载器使用。目录前缀以 `.` 结尾；精确类前缀同时覆盖 `$` 开头的协程、内部类与编译器生成类。
- 核心包 `code.jar` 的 DEX 只包含调度器、UI、Schema、账号状态及共享工具；兼容的默认功能包放在其中的 `features/<id>.jar`。APK 内置实现作为初次启动的回退。
- 每个独立功能使用一个子类加载器，只定义自己拥有的类。账号状态、配置、识别、屏幕导航及宿主 API 由活动核心与宿主提供，防止出现两份 `InGamesVars`。
- 功能间调用必须使用 `FeatureRuntime`，不能直接导入另一个功能的实现。`validateFeatureBoundaries` 检查编译后类的引用，违反边界会阻止打包。
- 功能实例在当前运行周期复用。启停、安装及恢复操作通过 `BotRuntime.stop()` 等待旧脚本退出后清理实例；重新运行时重新建立功能实例缓存。
- 非私有路径的文件与 Shell 操作继续经宿主 Root API；插件安装文件只写入宿主私有目录。

共享工具是功能包的核心 API。保持 API 1 期间必须保留已有类名、方法签名和行为契约；变更不兼容接口时需要同步提高 `FeatureArchive.API` 与构建的 `api` 字段，并发布匹配的宿主及核心包。新增插件 ID 或修改归属清单也需要更新宿主 APK。已有插件内部的兼容修改可以独立发布。

教程处理中的通用弹窗识别、登录中的存档复制和运行时切号等共享能力仍在公共核心；插件负责对应业务入口的调度和组合。

## 验证

```sh
./gradlew :app:buildJar :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest

# 连接设备后，仅执行无游戏动作的真实 DexClassLoader 验证：
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.coc.suncode.loadjar.FeatureLoadingTest
```

单元测试覆盖停用登录与可选功能、实例缓存和重新加载、取消传播、加载失败恢复、包描述校验、类归属边界。设备测试使用实际拆分的 DEX，检查功能类由自己的加载器定义、核心无法加载功能实现、所有功能共享同一核心状态类；不执行游戏触摸。
