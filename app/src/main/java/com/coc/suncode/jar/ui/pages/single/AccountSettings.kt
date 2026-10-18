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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.coc.suncode.jar.ui.components.CustomButton
import com.coc.suncode.jar.ui.components.SettingNumberStepper
import com.coc.suncode.jar.ui.components.SettingSection
import com.coc.suncode.jar.ui.schema.ConfigManager
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
    val count = GlobalVars.configStates[GLOBAL_SETTINGS.ACCOUNT_COUNT.key]?.value?.toIntOrNull()?.coerceIn(1, 10_000) ?: 1
    items(count = count, key = { "account-${it + 1}" }) { offset ->
        val index = offset + 1
        AccountRow(index, busyAccount, onLogin = { onLogin(index) }, onConfigure = { onConfigure(index) })
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
