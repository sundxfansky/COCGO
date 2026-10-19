@file:Suppress("FunctionName")

package com.coc.suncode.jar.ui.pages.single

import android.os.Environment
import com.coc.suncode.core.util.fileactions.SavePath
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coc.suncode.jar.ui.components.CustomButton
import com.coc.suncode.jar.ui.components.CustomNotificationWindow
import com.coc.suncode.jar.ui.components.SettingDropdown
import com.coc.suncode.jar.ui.components.SettingSection
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.jar.ui.components.SettingInputRow
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.jar.ui.schema.Schema.GLOBAL_SETTINGS
import com.coc.suncode.core.util.basic.ServerShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Game version enum
 * Centralized management of config keys, package names, folder names and subdirectories to extract for different versions (CN/Global)
 */
private enum class GameVariant(
    val settingKey: String,
    val folderName: String,
    val packageName: String,
    val targetSubDirs: List<String>
) {
    CN(
        settingKey = GLOBAL_SETTINGS.EXTRACT_CN.key,
        folderName = "sunCNGameSave",
        packageName = "com.tencent.tmgp.supercell.clashofclans",
        targetSubDirs = listOf("shared_prefs", "databases")
    ),
    GLOBAL(
        settingKey = GLOBAL_SETTINGS.EXTRACT_GLOBAL.key,
        folderName = "sunGlobalGameSave",
        packageName = "com.supercell.clashofclans",
        targetSubDirs = listOf("shared_prefs")
    )
}

fun LazyListScope.ExtractGameSave() {
    item {
        ExtractGameSaveContent()
    }
}

@Composable
fun ExtractGameSaveContent() {
    // State management
    var showDialog by remember { mutableStateOf(false) }
    var dialogMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    // Generic function to show dialog, auto-dismiss after 2 seconds
    fun showMsg(msg: String) {
        dialogMessage = msg
        showDialog = true
        coroutineScope.launch {
            delay(1200)
            showDialog = false
        }
    }

    // Extract logic
    fun performExtract(variant: GameVariant) {
        coroutineScope.launch(Dispatchers.IO) {
            val suffix = GlobalVars.configStates[variant.settingKey]!!.value
            // Reject traversal and command interpolation before touching saved games.
            if (!SavePath.isValid(suffix)) { showMsg("存档名只能包含文字、数字、点、横线和下划线"); return@launch }
            val sdPath = Environment.getExternalStorageDirectory().path

            // [Modification] Base directory added a layer of sunFiles
            // Path becomes: /sdcard/sunFiles/sunCNGameSave
            val gameRootDir = "$sdPath/sunFiles/${variant.folderName}"

            // Specific path for this save (e.g., /sdcard/sunFiles/sunCNGameSave/001)
            val targetSaveDir = "$gameRootDir/$suffix"

            // 1. Check if save already exists (use Shell to check shared_prefs folder)
            val checkExistCmd = "[ -d \"$targetSaveDir/shared_prefs\" ]"
            if (ServerShell.exec(checkExistCmd).isSuccess) {
                showMsg("提取失败，存档已存在")
                return@launch
            }

            showMsg("提取中...")

            // 2. Ensure directory structure exists
            // mkdir -p will recursively create directories: if sunFiles doesn't exist it will be created, if sunCNGameSave doesn't exist it will also be created
            ServerShell.exec("mkdir -p \"$gameRootDir\"")

            // Create directory for this save
            ServerShell.exec("mkdir -p \"$targetSaveDir\"")

            // 3. Iterate and copy directories
            variant.targetSubDirs.forEach { dir ->
                val destPath = "$targetSaveDir/$dir"
                // Create target subdirectory
                ServerShell.exec("mkdir -p \"$destPath\"")

                // 使用 Root 权限复制文件
                // 路径加引号防止空格问题
                val cmd = "cp -r /data/data/${variant.packageName}/$dir/* \"$destPath\""
                ServerShell.exec(cmd)
            }

            showMsg("提取成功！")
        }
    }

    // 删除逻辑
    fun performDelete(variant: GameVariant) {
        coroutineScope.launch(Dispatchers.IO) {
            val suffix = GlobalVars.configStates[variant.settingKey]!!.value
            // Reject traversal and command interpolation before touching saved games.
            if (!SavePath.isValid(suffix)) { showMsg("存档名只能包含文字、数字、点、横线和下划线"); return@launch }

            if (suffix.isEmpty()) {
                showMsg("错误：未获取到路径序号")
                return@launch
            }

            val sdPath = Environment.getExternalStorageDirectory().path
            // 【修改点】删除路径也同步增加 sunFiles
            val targetPath = "$sdPath/sunFiles/${variant.folderName}/$suffix"

            // 使用 Shell 检查目录是否存在
            val checkExistCmd = "[ -d \"$targetPath\" ]"
            if (!ServerShell.exec(checkExistCmd).isSuccess) {
                showMsg("删除失败，存档不存在")
                return@launch
            }

            showMsg("删除中...")

            // 执行删除
            val result = ServerShell.exec("rm -rf \"$targetPath\"")

            if (result.isSuccess) {
                showMsg("删除成功！")
            } else {
                showMsg("删除失败，请检查权限")
            }
        }
    }

    // Initialize and subscribe to version selector state so parent recomposes on change
    val versionState = GlobalVars.configStates.getOrPut(GLOBAL_SETTINGS.EXTRACT_VERSION.key) {
        mutableStateOf(Schema.getDefaultValue(GLOBAL_SETTINGS.EXTRACT_VERSION.key))
    }
    val selectedIndex = versionState.value.toIntOrNull() ?: 0
    val selectedVariant = GameVariant.entries.getOrElse(selectedIndex) { GameVariant.CN }

    Column(modifier = Modifier.padding(6.dp)) {
        // Dropdown to switch between CN and Global versions
        SettingDropdown(
            key = GLOBAL_SETTINGS.EXTRACT_VERSION.key,
            options = listOf("国服", "国际服")
        )

        // Only show the section for the currently selected variant
        SettingSection {
            GameConfigSection(
                variant = selectedVariant,
                onExtract = { performExtract(selectedVariant) },
                onDelete = { performDelete(selectedVariant) }
            )
        }
    }

    // 全局弹窗组件
    if (showDialog) {
        CustomNotificationWindow(
            message = dialogMessage,
            onDismissRequest = { }
        )
    }
}

/**
 * Extracted reusable UI component
 */
@Composable
private fun GameConfigSection(
    variant: GameVariant,
    onExtract: () -> Unit,
    onDelete: () -> Unit
) {
    SettingInputRow(key = variant.settingKey)
    Row {
        val regionName = if (variant == GameVariant.CN) "国服" else "国际服"
        CustomButton(text = "提取${regionName}数据", onClick = onExtract)
        CustomButton(text = "删除现有数据", onClick = onDelete)
    }
}