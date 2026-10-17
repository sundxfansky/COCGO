# 宿主内部 Root 接口

原来的 sunserver 文件与 Shell 功能已移入宿主。工作进程直接加载宿主 APK，以 Root 身份通过私有标准输入输出管道执行命令，不开放 HTTP/WebSocket 端口，不需要独立安装包。

`third_party/sunserver` 仅保留作历史源码参考。本文只描述当前实际支持的兼容 API。动态模块应调用宿主接口，不自行启动服务或建立 Root 网络连接。

## 调用入口

- `FileHelper`：JSON 文件读取、写入、删除和存在检查。
- `ServerShell.exec(command)`：Root Shell，返回 `code`、`out` 和 `isSuccess`。
- `RunShell`：脚本命令兼容入口，可在执行前等待辅助运行。
- `ServerActions`：配置加载、保存、内存状态和旧命令数据结构的兼容入口。

保留 `com.coc.suncode.core.data.websocket.ServerActions` 的包名和类型以兼容动态 JAR；包名不表示仍然使用 WebSocket。`MainCode.refreshConfigs(ServerActions)` 的宿主接口身份保持一致。

## 响应结构

```json
{"status":"success","data":"..."}
```

```json
{"status":"error","message":"..."}
```

Shell 返回示例：

```json
{"status":"success","exitCode":7,"data":"command output"}
```

Shell 命令退出码非零时仍会返回执行结果，调用方应检查 `exitCode` 或 `isSuccess`。输出包含标准输出与标准错误，并受执行超时和容量上限约束。取消或退出时释放后端资源；JSON 文本中的换行、中文与引号由序列化层编码。

## 文件操作兼容结构

以下数据供内部 `sendActionSync` 使用，不是可远程调用的网络 API。

```json
{
  "actionType":"file_action",
  "subAction":"write",
  "path":"/sdcard/sunFiles/example.json",
  "content":"{\"name\":\"示例\"}"
}
```

| subAction | 参数 | 成功 data |
|---|---|---|
| `read` | `path` | UTF-8 文本 |
| `write` | `path`、`content` | 写入的 UTF-8 字节数 |
| `create` | `path` | 创建成功信息 |
| `delete` | `path` | 删除成功信息 |
| `check_exists` | `path` | 字符串 `true` 或 `false` |
| `copy` | `path`、`destPath` | 字符串 `true` |
| `rename` | `path`、`newPath` | 字符串 `true` |

文件写入采用同目录临时文件、同步和替换，写入失败不会提前发布新的内存配置。配置路径继续使用 `/sdcard/sunFiles/sun_config.json`，已有配置不需要迁移。

## Shell 操作兼容结构

```json
{"actionType":"shell_action","command":"printf example"}
```

原有启动/停止游戏、存档备份/恢复、输入法等脚本继续调用 `ServerShell` 或 `RunShell`。命令只在宿主授权的内部通道执行。

## 触摸

触摸使用 `TouchActions -> RootTouchClient -> RootTouchProcess` 的独立执行通道，校验目标包名、UID 与前台状态。坐标、日志穿透和截图排除的限制见 [TargetInput.md](TargetInput.md)。
