package com.coc.suncode.jar.ui.pages.single

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.ui.floatingwindows.BotRuntime
import com.coc.suncode.core.util.basic.ShowMessage
import com.coc.suncode.interfaces.FeaturePluginClient
import com.coc.suncode.jar.ui.components.CloudConfigInput
import com.coc.suncode.jar.ui.components.CustomButton
import com.coc.suncode.jar.ui.components.SettingSection
import com.coc.suncode.loadjar.features.FeatureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun FeaturePluginsSettings() {
    val context = LocalContext.current
    val store = remember { FeatureStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf("login") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val versions = remember(revision) { store.specs.associate { it.id to store.version(it.id) } }

    fun change(operation: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                // Stop and join before changing feature availability or class-loader instances.
                GlobalVars.isAutoRunEnabled = false
                GlobalVars.isPlaying.value = false
                BotRuntime.stop()
                status = operation()
                // The user may have resumed the script while a download was in progress.
                GlobalVars.isPlaying.value = false
                BotRuntime.stop()
                (GlobalVars.pluginUI as? FeaturePluginClient)?.reloadFeatures()
                revision++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = "操作失败：${error.message}"
                ShowMessage.record(status, throwable = error)
            } finally { busy = false }
        }
    }

    SettingSection {
        Text("功能插件")
        Text("启停与安装后辅助保持暂停，点击保存并运行继续。登录停用时无法自动运行或切换账号。")
        store.specs.forEach { spec ->
            Row {
                CustomButton(
                    text = "${if (selected == spec.id) "✓ " else ""}${spec.name} · ${versions[spec.id]}",
                    enable = !busy,
                    onClick = { selected = spec.id }
                )
                CustomButton(
                    text = if (store.enabled(spec.id)) "停用" else "启用",
                    enable = !busy,
                    onClick = { change {
                        store.setEnabled(spec.id, !store.enabled(spec.id))
                        "${spec.name}已${if (store.enabled(spec.id)) "启用" else "停用"}"
                    } }
                )
            }
        }
        CloudConfigInput(label = "插件下载地址", value = url, onValueChange = { if (!busy) url = it })
        Text("选择功能后填写 HTTPS 地址；同目录需有对应的 .jar.sig 签名文件。")
        Row {
            CustomButton(text = if (busy) "处理中…" else "安装所选插件", enable = !busy && url.isNotBlank(), onClick = {
                val id = selected
                val address = url.trim()
                change {
                    val parent = requireNotNull(GlobalVars.pluginUI).javaClass.classLoader!!
                    val version = store.installUrl(id, address, parent)
                    "安装成功，版本 $version"
                }
            })
            CustomButton(text = "恢复所选内置插件", enable = !busy, onClick = {
                val id = selected
                change { store.useBundled(id); "已恢复内置插件" }
            })
        }
        if (status.isNotBlank()) Text(status)
    }
}
