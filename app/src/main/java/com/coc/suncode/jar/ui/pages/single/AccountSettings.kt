package com.coc.suncode.jar.ui.pages.single

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coc.suncode.core.data.database.GlobalVars
import com.coc.suncode.core.ui.theme.AppColors
import com.coc.suncode.jar.ui.components.CustomAlertDialog
import com.coc.suncode.jar.ui.components.CustomButton
import com.coc.suncode.jar.ui.components.SettingNumberStepper
import com.coc.suncode.jar.ui.components.SettingSection
import com.coc.suncode.jar.ui.components.SettingSwitchIcon
import com.coc.suncode.jar.ui.schema.ConfigManager
import com.coc.suncode.jar.ui.schema.Schema
import com.coc.suncode.jar.ui.schema.Schema.ACCOUNT_SETTINGS
import com.coc.suncode.jar.ui.schema.Schema.GLOBAL_SETTINGS

fun LazyListScope.AccountSettings(onConfigure: (Int) -> Unit, onLogin: (Int) -> Unit, busyAccount: Int? = null) {
    item {
        SettingSection {
            SettingNumberStepper(GLOBAL_SETTINGS.ACCOUNT_COUNT.key, 1..10_000) { value ->
                value.toIntOrNull()?.let(ConfigManager::expandAccountConfigs)
            }
            FlowRow {
                CustomButton(text = "一键全选", onClick = { setAllAccounts { true } })
                CustomButton(text = "一键反选", onClick = { setAllAccounts { !it } })
            }
        }
    }
    item {
        var expanded by rememberSaveable { mutableStateOf(false) }
        SettingSection {
            CustomButton(text = if (expanded) "收起存档提取" else "提取存档", onClick = { expanded = !expanded })
            if (expanded) ExtractGameSaveContent()
        }
    }
    item {
        var expanded by rememberSaveable { mutableStateOf(false) }
        SettingSection {
            CustomButton(text = if (expanded) "收起配置同步" else "同步配置到其他账号", enable = busyAccount == null, onClick = { expanded = !expanded })
            if (expanded) SyncAccountProfiles()
        }
    }
    val count = GlobalVars.configStates[GLOBAL_SETTINGS.ACCOUNT_COUNT.key]?.value?.toIntOrNull()?.coerceIn(1, 10_000) ?: 1
    items(count = count, key = { "account-${it + 1}" }) { offset ->
        val index = offset + 1
        AccountRow(index, busyAccount, onLogin = { onLogin(index) }, onConfigure = { onConfigure(index) })
    }
}

/** Copies one account's main-world (and optionally builder-base) profile to an inclusive account range. */
@Composable
private fun SyncAccountProfiles() {
    val count = GlobalVars.configStates[GLOBAL_SETTINGS.ACCOUNT_COUNT.key]?.value?.toIntOrNull()?.coerceIn(1, 10_000) ?: 1
    fun read(key: String) = GlobalVars.configStates[key]?.value?.toIntOrNull()
    val source = read(GLOBAL_SETTINGS.SYNC_SOURCE_ACCOUNT.key)
    val start = read(GLOBAL_SETTINGS.SYNC_TARGET_START.key)
    val end = read(GLOBAL_SETTINGS.SYNC_TARGET_END.key)
    val includeBuilderBase = GlobalVars.configStates[GLOBAL_SETTINGS.SYNC_INCLUDE_BUILDER_BASE.key]?.value == "1"
    var confirming by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    // Validation message, or null when the inputs describe a usable sync.
    val error = when {
        source == null || source !in 1..count -> "源账号需在 1 至 $count 之间"
        start == null || end == null || start !in 1..count || end !in 1..count -> "目标账号需在 1 至 $count 之间"
        start > end -> "目标起始账号不能大于结束账号"
        (start..end).all { it == source } -> "目标区间内没有除源账号外的账号"
        else -> null
    }
    val sourceName = Schema.getDisplayName(GLOBAL_SETTINGS.SYNC_SOURCE_ACCOUNT.key)
    val worldText = if (includeBuilderBase) "主世界和夜世界" else "主世界"

    Text("把源账号的配置复制到目标区间内的账号（包含首尾，跳过源账号本身）。账号信息、登录方式和 QQ 授权不会被复制。", style = MaterialTheme.typography.labelMedium)
    SettingNumberStepper(GLOBAL_SETTINGS.SYNC_SOURCE_ACCOUNT.key, 1..count) { result = "" }
    FlowRow {
        SettingNumberStepper(GLOBAL_SETTINGS.SYNC_TARGET_START.key, 1..count) { result = "" }
        SettingNumberStepper(GLOBAL_SETTINGS.SYNC_TARGET_END.key, 1..count) { result = "" }
    }
    SettingSwitchIcon(key = GLOBAL_SETTINGS.SYNC_INCLUDE_BUILDER_BASE.key, afterChange = { result = "" })
    if (error != null) Text(error, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    CustomButton(text = "开始同步", enable = error == null, onClick = { confirming = true })
    if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.labelMedium)

    if (confirming && error == null) {
        CustomAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("确认同步配置", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "将用账号 $source 的${worldText}配置覆盖账号 $start 至 $end（跳过账号 $source）。\n此操作会覆盖目标账号的现有配置，保存后生效。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
                TextButton(onClick = {
                    confirming = false
                    GlobalVars.isAutoRunEnabled = false
                    val synced = ConfigManager.syncAccountProfiles(source!!, start!!..end!!, includeBuilderBase)
                    result = "已将${sourceName} $source 的${worldText}配置同步到 $synced 个账号，请点击保存使其生效。"
                }) { Text("确认同步") }
            }
        )
    }
}

private fun setAllAccounts(next: (Boolean) -> Boolean) {
    val count = GlobalVars.configStates[GLOBAL_SETTINGS.ACCOUNT_COUNT.key]?.value?.toIntOrNull() ?: 1
    for (index in 1..count) {
        GlobalVars.configStates["${ACCOUNT_SETTINGS.ISOPEN.key}$index"]?.let { it.value = if (next(it.value == "1")) "1" else "0" }
    }
}

/** The list is a compact summary; editing belongs exclusively to the detail page. */
@Composable
private fun AccountRow(index: Int, busyAccount: Int?, onLogin: () -> Unit, onConfigure: () -> Unit) {
    val remark = GlobalVars.configStates["${ACCOUNT_SETTINGS.REMARK.key}$index"]?.value.orEmpty()
    val enabled = GlobalVars.configStates["${ACCOUNT_SETTINGS.ISOPEN.key}$index"]
    SettingSection {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (remark.isBlank()) "账号 $index" else "$index · $remark",
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium
            )
            Switch(
                checked = enabled?.value == "1",
                onCheckedChange = { GlobalVars.isAutoRunEnabled = false; enabled?.value = if (it) "1" else "0" },
                modifier = Modifier.width(44.dp).scale(0.7f),
                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Azure, checkedThumbColor = Color.White)
            )
            CustomButton(text = if (busyAccount == index) "上号中" else "上号", enable = busyAccount == null, onClick = onLogin, marginTop = 0.dp)
            CustomButton(text = "配置", enable = busyAccount == null, onClick = onConfigure, marginTop = 0.dp)
        }
    }
}
