# 目标应用输入

触摸链路为 `TouchActions -> RootTouchClient -> su app_process -> InputManager`。
工作进程直接加载宿主 APK，通过私有标准输入输出管道接收手势，不使用 sunserver、WebSocket 或额外安装包。
文件操作、配置、启动/关闭游戏等命令也已迁移到宿主内部 Root 后端，使用独立私有管道；无需启动 sunserver 或安装额外服务 APK。

动态模块在 `InGamesVars.currentGameVersion` 初始化或切换时调用宿主的 `selectTargetPackage`。
每段手势固定目标 UID 和显示 ID；注入前校验前台包名及真实 UID。
Android 13 及以上同时调用目标 UID 注入接口。目标切到后台、权限失败或输入被拒绝时，辅助暂停并释放触点。
这种方式仍使用目标应用在默认显示上的坐标，不支持后台游戏或独立虚拟显示。
升级时需同时更新宿主 APK 和动态插件，旧插件没有设置目标包名的接口调用。

日志由原悬浮窗 Service 托管独立 WindowManager 窗口，默认禁止接收触摸，真实窗口透明度不高于 0.75 及系统限制。
控制栏的日志按钮显示/关闭日志，历史按钮切换手动滚动模式。
自动手势期间临时强制穿透，结束后恢复浏览模式，不改变辅助运行状态。

识别截图前暂时隐藏日志，等待两帧后读取新的 Root 截图，结束后恢复。
这避免 MediaProjection 缓存旧日志画面；频繁截图时日志可能短暂闪动。
`FLAG_SECURE` 会遮黑被覆盖区域，因此不用于排除日志。

设备回归类：`RootTouchProcessTest`、`RootTouchClientTest`、`FloatingLogWindowTest`。
前两项只操作合成测试界面，客户端测试要求实际宿主包名已获得 Root 授权。
